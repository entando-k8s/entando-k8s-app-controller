package org.entando.kubernetes.controller.app;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.TlsAware;
import org.entando.kubernetes.model.app.EntandoApp;

public class AppBuilderDeployableContainer implements DeployableContainer, IngressingContainer, TlsAware {

    private static final String ENTANDO_APP_BUILDER_IMAGE_NAME = "entando/entando-app-builder-de";
    private final EntandoApp entandoApp;

    public AppBuilderDeployableContainer(EntandoApp entandoApp) {
        this.entandoApp = entandoApp;
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 512;
    }

    @Override
    public int getCpuLimitMillicores() {
        return 500;
    }

    @Override
    public String determineImageToUse() {
        return ENTANDO_APP_BUILDER_IMAGE_NAME;
    }

    @Override
    public String getNameQualifier() {
        return "appbuilder";
    }

    @Override
    public int getPort() {
        return 8081;
    }

    @Override
    public String getWebContextPath() {
        return "/app-builder/";
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of("/app-builder/index.html");
    }

    @Override
    public void addEnvironmentVariables(List<EnvVar> vars) {
        vars.add(new EnvVar("REACT_APP_DOMAIN", entandoApp.getSpec().getIngressPath().orElse("/entando-de-app"), null));
    }
}
