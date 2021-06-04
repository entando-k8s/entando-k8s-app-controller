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
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;

public class ComponentManagerDeployable extends AbstractEntandoAppDeployable implements DbAwareDeployable<EntandoAppDeploymentResult> {

    public static final long COMPONENT_MANAGER_CURRENT_USER = 185L;
    private final List<DeployableContainer> containers;

    public ComponentManagerDeployable(EntandoApp entandoApp,
            SsoConnectionInfo ssoConnectionInfo,
            EntandoK8SService entandoK8SService,
            DatabaseConnectionInfo databaseServiceResult) {
        super(entandoApp, ssoConnectionInfo);
        this.containers = Collections.singletonList(
                new ComponentManagerDeployableContainer(entandoApp, ssoConnectionInfo, entandoK8SService, databaseServiceResult)
        );
    }

    @Override
    public Optional<Long> getFileSystemUserAndGroupId() {
        return Optional.of(COMPONENT_MANAGER_CURRENT_USER);
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public Optional<String> getQualifier() {
        return Optional.of("cm");
    }

}
