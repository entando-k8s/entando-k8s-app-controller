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

import java.util.Collections;
import java.util.List;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.client.InfrastructureConfig;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;

public class ComponentManagerDeployable extends AbstractEntandoAppDeployable implements
        DbAwareDeployable {

    private final List<DeployableContainer> containers;

    public ComponentManagerDeployable(EntandoApp entandoApp,
            KeycloakConnectionConfig keycloakConnectionConfig,
            InfrastructureConfig infrastructureConfig,
            DatabaseServiceResult databaseServiceResult,
            EntandoAppDeploymentResult entandoAppDeployment) {
        super(entandoApp, keycloakConnectionConfig);
        this.containers = Collections.singletonList(
                new ComponentManagerDeployableContainer(entandoApp, keycloakConnectionConfig, infrastructureConfig, entandoAppDeployment,
                        databaseServiceResult)
        );
    }

    @Override
    public boolean hasContainersExpectingSchemas() {
        return entandoApp.getSpec().getDbms().map(v -> v != DbmsVendor.NONE && v != DbmsVendor.EMBEDDED).orElse(false);
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public String getNameQualifier() {
        return "cm";
    }

}
