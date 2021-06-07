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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.KeycloakPreference;
import org.entando.kubernetes.controller.spi.container.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DbAwareContainer;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.PersistentVolumeAwareContainer;
import org.entando.kubernetes.controller.spi.container.SecretToMount;
import org.entando.kubernetes.controller.spi.container.SpringBootDeployableContainer;
import org.entando.kubernetes.controller.spi.container.SsoAwareContainer;
import org.entando.kubernetes.controller.spi.container.SsoClientConfig;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.KeycloakToUse;
import org.entando.kubernetes.model.common.Permission;

public class ComponentManagerDeployableContainer
        implements SpringBootDeployableContainer, PersistentVolumeAwareContainer, ParameterizableContainer, SsoAwareContainer,
        KeycloakPreference {

    public static final String COMPONENT_MANAGER_QUALIFIER = "de";
    public static final String COMPONENT_MANAGER_IMAGE_NAME = "entando/entando-component-manager";

    private static final String DEDB = "dedb";
    public static final String ECR_GIT_CONFIG_DIR = "/etc/ecr-git-config";
    private final EntandoApp entandoApp;
    private final SsoConnectionInfo keycloakConnectionConfig;
    private final EntandoK8SService infrastructureConfig;
    private final List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfo;

    public ComponentManagerDeployableContainer(
            EntandoApp entandoApp,
            SsoConnectionInfo keycloakConnectionConfig,
            EntandoK8SService infrastructureConfig,
            DatabaseConnectionInfo databaseServiceResult) {
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
        this.infrastructureConfig = infrastructureConfig;
        this.databaseSchemaConnectionInfo = Optional.ofNullable(databaseServiceResult)
                .map(dsr -> DbAwareContainer.buildDatabaseSchemaConnectionInfo(entandoApp, dsr, Collections.singletonList(DEDB)))
                .orElse(Collections.emptyList());
    }

    @Override
    public Optional<String> getStorageClass() {
        return Optional
                .ofNullable(
                        entandoApp.getSpec().getStorageClass().orElse(PersistentVolumeAwareContainer.super.getStorageClass().orElse(null)));
    }

    @Override
    public String determineImageToUse() {
        return COMPONENT_MANAGER_IMAGE_NAME;
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
        vars.add(new EnvVar("ENTANDO_K8S_SERVICE_URL", "http://" + infrastructureConfig.getInternalServiceHostname() + "/k8s", null));
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
        return 768;
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
    public Optional<KeycloakToUse> getPreferredKeycloakToUse() {
        return entandoApp.getSpec().getKeycloakToUse();
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        String entandoAppClientId = EntandoAppDeployableContainer.clientIdOf(entandoApp);
        String clientId = entandoApp.getMetadata().getName() + "-" + getNameQualifier();
        List<Permission> permissions = new ArrayList<>();
        permissions.add(new Permission(entandoAppClientId, "superuser"));
        return new SsoClientConfig(getRealmToUse(), clientId, clientId,
                Collections.emptyList(),
                permissions);
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
