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
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.IngressingContainer;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.model.app.EntandoApp;

public class AppBuilderDeployableContainer implements DeployableContainer, IngressingContainer, ParameterizableContainer {

    private static final String ENTANDO_APP_BUILDER_IMAGE_NAME = "app-builder";
    private final EntandoApp entandoApp;

    public AppBuilderDeployableContainer(EntandoApp entandoApp) {
        this.entandoApp = entandoApp;
    }

    @Override
    public Optional<Integer> getMaximumStartupTimeSeconds() {
        return Optional.of(120);
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
        return EntandoAppHelper.appendImageVersion(entandoApp, ENTANDO_APP_BUILDER_IMAGE_NAME);
    }

    @Override
    public String getNameQualifier() {
        return "appbuilder";
    }

    @Override
    public int getPrimaryPort() {
        return 8081;
    }

    @Override
    public String getWebContextPath() {
        return "/app-builder/";
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of("/app-builder/favicon-entando.png");
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("DOMAIN", EntandoAppHelper.getNormalizedDeAppWebContextPath(entandoApp),
                null));
        return vars;
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return entandoApp.getSpec().getEnvironmentVariables();
    }
}
