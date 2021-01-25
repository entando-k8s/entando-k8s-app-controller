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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.IngressingDeployCommand;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.DockerImageInfo;
import org.entando.kubernetes.controller.database.DatabaseSchemaCreationResult;
import org.entando.kubernetes.controller.spi.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.KeycloakAware;
import org.entando.kubernetes.controller.spi.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.PortSpec;
import org.entando.kubernetes.controller.spi.TlsAware;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoIngressingDeploymentSpec;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.KeycloakAwareSpec;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppSpec;

public class EntandoAppDeployableContainer implements IngressingContainer, PersistentVolumeAware,
        KeycloakAware, DbAware, TlsAware, ParameterizableContainer, ConfigurableResourceContainer {

    public static final String INGRESS_WEB_CONTEXT = "/entando-de-app";
    public static final int PORT = 8080;
    public static final String HEALTH_CHECK = "/api/health";
    private static final String PORTDB = "portdb";
    private static final String SERVDB = "servdb";
    private static final String PORTDB_PREFIX = "PORTDB_";
    private static final String SERVDB_PREFIX = "SERVDB_";
    private final EntandoApp entandoApp;
    private final KeycloakConnectionConfig keycloakConnectionConfig;
    private Map<String, DatabaseSchemaCreationResult> dbSchemas = new ConcurrentHashMap<>();
    private DbmsVendor dbmsVendor;

    public EntandoAppDeployableContainer(EntandoApp entandoApp, KeycloakConnectionConfig keycloakConnectionConfig) {
        this.dbmsVendor = entandoApp.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED);
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
    }

    public static String clientIdOf(EntandoApp entandoApp) {
        //TOOD may have to prefix namespace
        return entandoApp.getMetadata().getName() + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public String determineImageToUse() {
        EntandoAppSpec spec = entandoApp.getSpec();
        return spec.getCustomServerImage().orElse(spec.getStandardServerImage().orElse(JeeServer.WILDFLY).getImageName());
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
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("DB_STARTUP_CHECK", "false", null));
        addEntandoDbConnectionVars(vars, this.dbSchemas.get(PORTDB), PORTDB_PREFIX);
        addEntandoDbConnectionVars(vars, this.dbSchemas.get(SERVDB), SERVDB_PREFIX);
        vars.add(new EnvVar("JGROUPS_CLUSTER_PASSWORD", RandomStringUtils.randomAlphanumeric(10), null));
        vars.add(new EnvVar("JGROUPS_JOIN_TIMEOUT", "3000", null));
        String labelExpression = IngressingDeployCommand.DEPLOYMENT_LABEL_NAME + "=" + entandoApp.getMetadata().getName() + "-"
                + KubeUtils.DEFAULT_SERVER_QUALIFIER;
        if (entandoApp.getSpec().getStandardServerImage().orElse(JeeServer.WILDFLY) == JeeServer.EAP) {
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
        return new KeycloakClientConfig(determineRealm(),
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

    protected DatabasePopulator buildDatabasePopulator() {
        return new EntandoAppDatabasePopulator(this);
    }

    @Override
    public void addDatabaseConnectionVariables(List<EnvVar> list) {
        //Done in superclass. One day we will implement this method in the superclass
    }

    @Override
    public EntandoIngressingDeploymentSpec getCustomResourceSpec() {
        return getKeycloakAwareSpec();
    }

    @Override
    public KeycloakAwareSpec getKeycloakAwareSpec() {
        return this.entandoApp.getSpec();
    }

    private void addEntandoDbConnectionVars(List<EnvVar> vars, DatabaseSchemaCreationResult dbDeploymentResult,
            String varNamePrefix) {

        if (dbDeploymentResult == null) {
            vars.add(new EnvVar(varNamePrefix + "DRIVER", "derby", null));
        } else {
            String jdbcUrl = dbDeploymentResult.getJdbcUrl();
            vars.add(new EnvVar(varNamePrefix + "URL", jdbcUrl, null));
            vars.add(new EnvVar(varNamePrefix + "USERNAME", null,
                    KubeUtils.secretKeyRef(dbDeploymentResult.getSchemaSecretName(), KubeUtils.USERNAME_KEY)));
            vars.add(new EnvVar(varNamePrefix + "PASSWORD", null,
                    KubeUtils.secretKeyRef(dbDeploymentResult.getSchemaSecretName(), KubeUtils.PASSSWORD_KEY)));

            JbossDatasourceValidation jbossDatasourceValidation = JbossDatasourceValidation.getValidConnectionCheckerClass(this.dbmsVendor);
            vars.add(new EnvVar(varNamePrefix + "CONNECTION_CHECKER", jbossDatasourceValidation.getValidConnectionCheckerClassName(),
                    null));
            vars.add(new EnvVar(varNamePrefix + "EXCEPTION_SORTER", jbossDatasourceValidation.getExceptionSorterClassName(),
                    null));
        }

    }

    @Override
    public List<String> getDbSchemaQualifiers() {
        return Arrays.asList(PORTDB, SERVDB);
    }

    @Override
    public Optional<DatabasePopulator> useDatabaseSchemas(Map<String, DatabaseSchemaCreationResult> dbSchemas) {
        this.dbSchemas = Optional.ofNullable(dbSchemas).orElse(new ConcurrentHashMap<>());
        return Optional.of(buildDatabasePopulator());
    }

    @Override
    public List<EnvVar> getDatabaseConnectionVariables() {
        return ???;
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
        public String[] getCommand() {
            return new String[]{"/bin/bash", "-c", "/entando-common/init-db-from-deployment.sh"};
        }

        @Override
        public List<EnvVar> getEnvironmentVariables() {
            return entandoAppDeployableContainer.getEnvironmentVariables();
        }

    }
}
