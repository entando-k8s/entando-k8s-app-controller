/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.app;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.container.DatabasePopulator;
import org.entando.kubernetes.controller.spi.container.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DbAware;
import org.entando.kubernetes.controller.spi.container.DockerImageInfo;
import org.entando.kubernetes.controller.spi.container.IngressingContainer;
import org.entando.kubernetes.controller.spi.container.KeycloakClientConfig;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.container.PortSpec;
import org.entando.kubernetes.controller.spi.container.TrustStoreAware;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.spibase.KeycloakAwareContainerBase;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoResourceRequirements;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.KeycloakAwareSpec;
import org.entando.kubernetes.model.app.EntandoApp;

public class EntandoAppDeployableContainer
        implements IngressingContainer, PersistentVolumeAware, KeycloakAwareContainerBase, DbAware, TrustStoreAware,
        ParameterizableContainer,
        ConfigurableResourceContainer {

    public static final String INGRESS_WEB_CONTEXT = "/entando-de-app";
    public static final int PORT = 8080;
    public static final String HEALTH_CHECK = "/api/health";
    private static final String PORTDB = "portdb";
    private static final String SERVDB = "servdb";
    private static final int PORTDB_IDX = 0;
    private static final int SERVDB_IDX = 1;
    private static final String PORTDB_PREFIX = "PORTDB_";
    private static final String SERVDB_PREFIX = "SERVDB_";
    private final EntandoApp entandoApp;
    private final KeycloakConnectionConfig keycloakConnectionConfig;
    private final List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfo;
    private final DbmsVendor dbmsVendor;

    public EntandoAppDeployableContainer(EntandoApp entandoApp, KeycloakConnectionConfig keycloakConnectionConfig,
            DatabaseServiceResult databaseServiceResult) {
        this.dbmsVendor = entandoApp.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED);
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
        this.databaseSchemaConnectionInfo = Optional.ofNullable(databaseServiceResult)
                .map(dsr -> DbAware.buildDatabaseSchemaConnectionInfo(entandoApp, dsr, Arrays.asList(PORTDB, SERVDB)))
                .orElse(Collections.emptyList());

    }

    public static String clientIdOf(EntandoApp entandoApp) {
        //TOOD may have to prefix namespace
        return entandoApp.getMetadata().getName() + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public String determineImageToUse() {
        return entandoApp.getSpec().getCustomServerImage().orElse(determineStandardImage().getImageName());
    }

    private JeeServer determineStandardImage() {
        if (EntandoOperatorSpiConfig.getComplianceMode() == EntandoOperatorComplianceMode.REDHAT) {
            return JeeServer.EAP;
        } else {
            return entandoApp.getSpec().getStandardServerImage().orElse(JeeServer.WILDFLY);
        }
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 1024 + 768;
    }

    @Override
    public int getCpuLimitMillicores() {
        return 1500;
    }

    @Override
    public String getNameQualifier() {
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("JGROUPS_CLUSTER_PASSWORD", SecretUtils.randomAlphanumeric(10), null));
        vars.add(new EnvVar("JGROUPS_JOIN_TIMEOUT", "3000", null));
        String labelExpression = KubeUtils.DEPLOYMENT_LABEL_NAME + "=" + entandoApp.getMetadata().getName() + "-"
                + NameUtils.DEFAULT_SERVER_QUALIFIER;
        if (determineStandardImage() == JeeServer.EAP) {
            vars.add(new EnvVar("JGROUPS_PING_PROTOCOL", "openshift.KUBE_PING", null));
            vars.add(new EnvVar("OPENSHIFT_KUBE_PING_NAMESPACE", entandoApp.getMetadata().getNamespace(), null));
            vars.add(new EnvVar("OPENSHIFT_KUBE_PING_LABELS", labelExpression, null));
        } else {
            vars.add(new EnvVar("KUBERNETES_NAMESPACE", entandoApp.getMetadata().getNamespace(), null));
            vars.add(new EnvVar("KUBERNETES_LABELS", labelExpression, null));
        }
        return vars;
    }

    @Override
    public int getPrimaryPort() {
        return PORT;
    }

    @Override
    public List<PortSpec> getAdditionalPorts() {
        return Arrays.asList(new PortSpec("ping", 8888), new PortSpec("ping2", 7600));
    }

    public KeycloakConnectionConfig getKeycloakConnectionConfig() {
        return keycloakConnectionConfig;
    }

    @Override
    public KeycloakClientConfig getKeycloakClientConfig() {
        String clientId = clientIdOf(this.entandoApp);
        return new KeycloakClientConfig(getKeycloakRealmToUse(),
                clientId,
                clientId).withRole("superuser").withPermission("realm-management", "realm-admin");
    }

    @Override
    public String getWebContextPath() {
        return entandoApp.getSpec().getIngressPath().orElse(INGRESS_WEB_CONTEXT);
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath() + HEALTH_CHECK);
    }

    @Override
    public String getVolumeMountPath() {
        return "/entando-data";
    }

    @Override
    public KeycloakAwareSpec getKeycloakAwareSpec() {
        return this.entandoApp.getSpec();
    }

    @Override
    public Optional<DatabasePopulator> getDatabasePopulator() {
        return Optional.of(new EntandoAppDatabasePopulator(this));
    }

    private void addEntandoDbConnectionVars(List<EnvVar> vars, int schemaIndex, String varNamePrefix) {

        if (dbmsVendor == DbmsVendor.EMBEDDED) {
            vars.add(new EnvVar(varNamePrefix + "DRIVER", "derby", null));
        } else if (dbmsVendor != DbmsVendor.NONE) {
            DatabaseSchemaConnectionInfo connectionInfo = this.databaseSchemaConnectionInfo.get(schemaIndex);
            String jdbcUrl = connectionInfo.getJdbcUrl();
            vars.add(new EnvVar(varNamePrefix + "URL", jdbcUrl, null));
            vars.add(new EnvVar(varNamePrefix + "USERNAME", null,
                    SecretUtils.secretKeyRef(connectionInfo.getSchemaSecretName(), SecretUtils.USERNAME_KEY)));
            vars.add(new EnvVar(varNamePrefix + "PASSWORD", null,
                    SecretUtils.secretKeyRef(connectionInfo.getSchemaSecretName(), SecretUtils.PASSSWORD_KEY)));

            JbossDatasourceValidation jbossDatasourceValidation = JbossDatasourceValidation.getValidConnectionCheckerClass(this.dbmsVendor);
            vars.add(new EnvVar(varNamePrefix + "CONNECTION_CHECKER", jbossDatasourceValidation.getValidConnectionCheckerClassName(),
                    null));
            vars.add(new EnvVar(varNamePrefix + "EXCEPTION_SORTER", jbossDatasourceValidation.getExceptionSorterClassName(),
                    null));
        }

    }

    @Override
    public List<EnvVar> getDatabaseConnectionVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("DB_STARTUP_CHECK", "false", null));
        addEntandoDbConnectionVars(vars, PORTDB_IDX, PORTDB_PREFIX);
        addEntandoDbConnectionVars(vars, SERVDB_IDX, SERVDB_PREFIX);
        return vars;
    }

    @Override
    public List<DatabaseSchemaConnectionInfo> getSchemaConnectionInfo() {
        return this.databaseSchemaConnectionInfo;
    }

    @Override
    public Optional<EntandoResourceRequirements> getResourceRequirementsOverride() {
        return getKeycloakAwareSpec().getResourceRequirements();
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return getKeycloakAwareSpec().getEnvironmentVariables();
    }

    /**
     * EntandoAppDatabasePopulator class.
     */
    public static class EntandoAppDatabasePopulator implements DatabasePopulator {

        private final EntandoAppDeployableContainer entandoAppDeployableContainer;

        public EntandoAppDatabasePopulator(EntandoAppDeployableContainer entandoAppDeployableContainer) {
            this.entandoAppDeployableContainer = entandoAppDeployableContainer;
        }

        @Override
        public DockerImageInfo getDockerImageInfo() {
            return entandoAppDeployableContainer.getDockerImageInfo();
        }

        @Override
        public List<String> getCommand() {
            return Arrays.asList("/bin/bash", "-c", "/entando-common/init-db-from-deployment.sh");
        }

        @Override
        public List<EnvVar> getEnvironmentVariables() {
            return entandoAppDeployableContainer.getDatabaseConnectionVariables();
        }

    }
}
