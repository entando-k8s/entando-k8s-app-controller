package org.entando.kubernetes.controller.app;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.EntandoDatabaseConsumingContainer;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.KeycloakAware;
import org.entando.kubernetes.controller.spi.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.TlsAware;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppSpec;

public class EntandoAppDeployableContainer extends EntandoDatabaseConsumingContainer implements IngressingContainer, PersistentVolumeAware,
        KeycloakAware, DbAware, TlsAware {

    public static final String INGRESS_WEB_CONTEXT = "/entando-de-app";
    public static final int PORT = 8080;
    private final EntandoApp entandoApp;
    private final KeycloakConnectionConfig keycloakConnectionConfig;

    public EntandoAppDeployableContainer(EntandoApp entandoApp, KeycloakConnectionConfig keycloakConnectionConfig) {
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
    public int getPort() {
        return PORT;
    }

    public KeycloakConnectionConfig getKeycloakConnectionConfig() {
        return keycloakConnectionConfig;
    }

    @Override
    public KeycloakClientConfig getKeycloakClientConfig() {
        String clientId = clientIdOf(this.entandoApp);
        return new KeycloakClientConfig(KubeUtils.ENTANDO_KEYCLOAK_REALM,
                clientId,
                clientId).withRole("superuser").withPermission("realm-management", "realm-admin");
    }

    @Override
    public String getWebContextPath() {
        return entandoApp.getSpec().getIngressPath().orElse(INGRESS_WEB_CONTEXT);
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath());
    }

    @Override
    public String getVolumeMountPath() {
        return "/entando-data";
    }

    @Override
    protected DatabasePopulator buildDatabasePopulator() {
        return new EntandoAppDatabasePopulator(this);
    }

    @Override
    public void addDatabaseConnectionVariables(List<EnvVar> list) {
        //Done in superclass. One day we will implement this method in the superclass
    }
}
