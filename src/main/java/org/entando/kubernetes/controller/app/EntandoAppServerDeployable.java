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
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.model.app.EntandoApp;

public class EntandoAppServerDeployable extends AbstractEntandoAppDeployable implements DbAwareDeployable<EntandoAppDeploymentResult> {
    private final List<DeployableContainer> containers;

    public EntandoAppServerDeployable(EntandoApp entandoApp,
            KeycloakConnectionConfig keycloakConnectionConfig,
            DatabaseServiceResult databaseServiceResult) {
        super(entandoApp, keycloakConnectionConfig);
        this.containers = Collections.singletonList(
                new EntandoAppDeployableContainer(entandoApp, keycloakConnectionConfig, databaseServiceResult)
        );
    }


    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public String getNameQualifier() {
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

}
