package org.entando.kubernetes.controller.app;

import java.util.Optional;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.TlsAware;

public class AppBuilderDeployableContainer implements DeployableContainer, IngressingContainer, TlsAware {

    private static final String ENTANDO_APP_BUILDER_IMAGE_NAME = "entando/entando-app-builder-de";

    public AppBuilderDeployableContainer() {
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

}
