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
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.spi.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;

public class EntandoAppServerDeployable extends AbstractEntandoAppDeployable implements
        DbAwareDeployable {

    /**
     * The operating system level id of the default user in the EAP and Wildfly base images. Was determined to be 185 running the 'id'
     * command in the entando/entando-eap72-clusted-base image or entando/entando-wildfly17-base image
     */
    public static final long DEFAULT_USERID_IN_JBOSS_BASE_IMAGES = 185L;
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
    public Optional<Long> getFileSystemUserAndGroupId() {
        return Optional.of(DEFAULT_USERID_IN_JBOSS_BASE_IMAGES);
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
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

}
