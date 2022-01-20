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

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase.lookupProperty;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.spi.container.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DbAwareContainer;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.PersistentVolumeAwareContainer;
import org.entando.kubernetes.controller.spi.container.SecretToMount;
import org.entando.kubernetes.controller.spi.container.SpringBootDeployableContainer;
import org.entando.kubernetes.controller.spi.container.SsoAwareContainer;
import org.entando.kubernetes.controller.spi.deployable.SsoClientConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.common.DbmsVendor;

public class ComponentManagerDeployableContainer
        implements SpringBootDeployableContainer, PersistentVolumeAwareContainer, ParameterizableContainer, SsoAwareContainer {

    public static final String COMPONENT_MANAGER_QUALIFIER = "de";
    public static final String COMPONENT_MANAGER_IMAGE_NAME = "entando-component-manager";

    private static final String DEDB = "dedb";
    public static final String ECR_GIT_CONFIG_DIR = "/etc/ecr-git-config";
    private final EntandoApp entandoApp;
    private final SsoConnectionInfo keycloakConnectionConfig;
    private final EntandoK8SService infrastructureConfig;
    private final List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfo;
    private SsoClientConfig ssoClientConfig;

    public ComponentManagerDeployableContainer(
            EntandoApp entandoApp,
            SsoConnectionInfo keycloakConnectionConfig,
            EntandoK8SService infrastructureConfig,
            DatabaseConnectionInfo databaseServiceResult,
            SsoClientConfig ssoClientConfig) {
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
        this.infrastructureConfig = infrastructureConfig;
        this.ssoClientConfig = ssoClientConfig;
        this.databaseSchemaConnectionInfo = ofNullable(databaseServiceResult)
                .map(dsr -> DbAwareContainer.buildDatabaseSchemaConnectionInfo(entandoApp, dsr, Collections.singletonList(DEDB)))
                .orElse(emptyList());
    }

    @Override
    public Optional<Integer> getMaximumStartupTimeSeconds() {
        return Optional.of(120);
    }

    @Override
    public Optional<String> getStorageClass() {
        return entandoApp.getSpec().getStorageClass().or(PersistentVolumeAwareContainer.super::getStorageClass);
    }

    @Override
    public String determineImageToUse() {
        return EntandoAppHelper.appendImageVersion(entandoApp, COMPONENT_MANAGER_IMAGE_NAME);
    }

    @Override
    public String getNameQualifier() {
        return COMPONENT_MANAGER_QUALIFIER;
    }

    @Override
    public int getPrimaryPort() {
        return 8083;
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        String entandoUrl = EntandoAppDeployableContainer.determineEntandoServiceBaseUrl(this.entandoApp);
        vars.add(new EnvVar("ENTANDO_APP_NAME", entandoApp.getMetadata().getName(), null));
        vars.add(new EnvVar("ENTANDO_URL", entandoUrl, null));
        vars.add(new EnvVar("SERVER_PORT", String.valueOf(getPrimaryPort()), null));
        List<String> ecrNamespacesToUse = ofNullable(entandoApp.getSpec().getComponentRepositoryNamespaces()).orElse(emptyList());
        if (ecrNamespacesToUse.isEmpty()) {
            ecrNamespacesToUse = lookupProperty(EntandoAppConfigProperty.ENTANDO_COMPONENT_REPOSITORY_NAMESPACES)
                    .map(s -> Arrays.asList(s.split(EntandoOperatorConfigBase.SEPERATOR_PATTERN)))
                    .orElse(emptyList());
        }
        if (!ecrNamespacesToUse.isEmpty()) {
            vars.add(new EnvVar("ENTANDO_COMPONENT_REPOSITORY_NAMESPACES", String.join(",", ecrNamespacesToUse), null));
        }
        vars.add(
                new EnvVar("ENTANDO_K8S_SERVICE_URL", format("http://%s:%s/k8s", infrastructureConfig.getInternalServiceHostname(),
                        infrastructureConfig.getService().getSpec().getPorts().get(0).getPort()), null));
        //The ssh files will be copied to /opt/.ssh and chmod to 400. This can only happen at runtime because Openshift generates a
        // random userid
        entandoApp.getSpec().getEcrGitSshSecretName().ifPresent(s -> vars.add(new EnvVar("GIT_SSH_COMMAND", "ssh "
                + "-o UserKnownHostsFile=/opt/.ssh/known_hosts "
                + "-i /opt/.ssh/id_rsa "
                + "-o IdentitiesOnly=yes", null)));
        return vars;
    }

    @Override
    public Optional<DbmsVendor> getDbms() {
        return Optional.of(entandoApp.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED));
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        return ssoClientConfig;
    }

    @Override
    public List<DatabaseSchemaConnectionInfo> getSchemaConnectionInfo() {
        return this.databaseSchemaConnectionInfo;
    }

    @Override
    public List<SecretToMount> getSecretsToMount() {
        List<SecretToMount> result = new ArrayList<>();
        entandoApp.getSpec().getEcrGitSshSecretName().ifPresent(s -> result.add(new SecretToMount(s, ECR_GIT_CONFIG_DIR)));
        return result;
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 1200;
    }

    @Override
    public Optional<DatabaseSchemaConnectionInfo> getDatabaseSchema() {
        return databaseSchemaConnectionInfo.stream().findFirst();
    }

    @Override
    public int getCpuLimitMillicores() {
        return 750;
    }

    @Override
    public SsoConnectionInfo getSsoConnectionInfo() {
        return keycloakConnectionConfig;
    }

    @Override
    public String getWebContextPath() {
        return "/digital-exchange";
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath() + "/actuator/health");
    }

    @Override
    public String getVolumeMountPath() {
        return "/entando-data";
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return entandoApp.getSpec().getEnvironmentVariables();
    }

}
