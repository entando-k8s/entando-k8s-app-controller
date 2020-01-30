package org.entando.kubernetes.controller.app;

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.InfrastructureConfig;
import org.entando.kubernetes.controller.database.DatabaseSchemaCreationResult;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.KeycloakAware;
import org.entando.kubernetes.controller.spi.TlsAware;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.plugin.Permission;

public class ComponentManagerDeployableContainer implements KeycloakAware, IngressingContainer, DbAware, TlsAware {

    public static final String COMPONENT_MANAGER_QUALIFIER = "de";
    public static final String COMPONENT_MANAGER_IMAGE_NAME = "entando/entando-component-manager";

    private static final String DEDB = "dedb";
    private final EntandoApp entandoApp;
    private final KeycloakConnectionConfig keycloakConnectionConfig;
    private final Optional<InfrastructureConfig> infrastructureConfig;
    private Map<String, DatabaseSchemaCreationResult> dbSchemas;

    public ComponentManagerDeployableContainer(EntandoApp entandoApp, KeycloakConnectionConfig keycloakConnectionConfig,
            InfrastructureConfig infrastructureConfig) {
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
        this.infrastructureConfig = Optional.ofNullable(infrastructureConfig);
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
    public int getPort() {
        return 8083;
    }

    @Override
    public void addEnvironmentVariables(List<EnvVar> vars) {
        String entandoUrl = format("http://localhost:%s%s", EntandoAppDeployableContainer.PORT,
                EntandoAppDeployableContainer.INGRESS_WEB_CONTEXT);
        vars.add(new EnvVar("ENTANDO_APP_NAME", entandoApp.getMetadata().getName(), null));
        vars.add(new EnvVar("ENTANDO_URL", entandoUrl, null));
        vars.add(new EnvVar("SERVER_PORT", String.valueOf(getPort()), null));
        infrastructureConfig.ifPresent(c -> vars.add(new EnvVar("ENTANDO_K8S_SERVICE_URL", c.getK8SExternalServiceUrl(), null)));
        DatabaseSchemaCreationResult databaseSchemaCreationResult = dbSchemas.get(DEDB);
        vars.add(new EnvVar("DB_VENDOR", databaseSchemaCreationResult.getVendor().getName(),
                null));
        vars.add(new EnvVar("SPRING_DATASOURCE_URL", databaseSchemaCreationResult.getJdbcUrl(),
                null));
        vars.add(new EnvVar("SPRING_DATASOURCE_USERNAME", null,
                KubeUtils.secretKeyRef(databaseSchemaCreationResult.getSchemaSecretName(), KubeUtils.USERNAME_KEY)));
        vars.add(new EnvVar("SPRING_JPA_DATABASE_PLATFORM",
                databaseSchemaCreationResult.getVendor().getHibernateDialect(), null));
        vars.add(new EnvVar("SPRING_DATASOURCE_PASSWORD", null,
                KubeUtils.secretKeyRef(databaseSchemaCreationResult.getSchemaSecretName(), KubeUtils.PASSSWORD_KEY)));
        databaseSchemaCreationResult.addAdditionalConfigFromDatabaseSecret(vars);
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 768;
    }

    @Override
    public int getCpuLimitMillicores() {
        return 750;
    }

    public KeycloakConnectionConfig getKeycloakConnectionConfig() {
        return keycloakConnectionConfig;
    }

    @Override
    public KeycloakClientConfig getKeycloakClientConfig() {
        String entandoAppClientId = EntandoAppDeployableContainer.clientIdOf(entandoApp);
        String clientId = entandoApp.getMetadata().getName() + "-" + getNameQualifier();
        List<Permission> permissions = new ArrayList<>();
        permissions.add(new Permission(entandoAppClientId, "superuser"));
        this.infrastructureConfig.ifPresent(c -> permissions.add(new Permission(c.getK8sServiceClientId(), KubeUtils.ENTANDO_APP_ROLE)));
        return new KeycloakClientConfig(KubeUtils.ENTANDO_KEYCLOAK_REALM, clientId, clientId,
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
    public List<String> getDbSchemaQualifiers() {
        return Arrays.asList(DEDB);
    }

    @Override
    public Optional<DatabasePopulator> useDatabaseSchemas(Map<String, DatabaseSchemaCreationResult> dbSchemas) {
        this.dbSchemas = dbSchemas;
        return Optional.empty();
    }

}
