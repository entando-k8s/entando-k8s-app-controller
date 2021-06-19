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
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.deployable.PublicIngressingDeployable;
import org.entando.kubernetes.controller.spi.deployable.SsoClientConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.common.KeycloakToUse;

public class EntandoAppServerDeployable
        extends AbstractEntandoAppDeployable
        implements DbAwareDeployable<EntandoAppDeploymentResult>, PublicIngressingDeployable<EntandoAppDeploymentResult> {

    private final List<DeployableContainer> containers;
    private final SsoConnectionInfo ssoConnectionInfo;

    public EntandoAppServerDeployable(EntandoApp entandoApp,
            SsoConnectionInfo ssoConnectionInfo,
            DatabaseConnectionInfo databaseServiceResult) {
        super(entandoApp);
        this.ssoConnectionInfo = ssoConnectionInfo;
        this.containers = Collections.singletonList(
                new EntandoAppDeployableContainer(entandoApp, ssoConnectionInfo, databaseServiceResult, getSsoClientConfig())
        );
    }

    @Override
    public Optional<String> getPublicClientId() {
        return Optional.ofNullable(publicClientIdOf(this.entandoApp));
    }

    public static String publicClientIdOf(EntandoApp entandoApp) {
        return entandoApp.getSpec().getKeycloakToUse().flatMap(KeycloakToUse::getPublicClientId).orElse(KeycloakName.PUBLIC_CLIENT_ID);
    }

    public static String clientIdOf(EntandoApp entandoApp) {
        return entandoApp.getMetadata().getName();
    }

    @Override
    public SsoConnectionInfo getSsoConnectionInfo() {
        return ssoConnectionInfo;
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        String clientId = clientIdOf(this.entandoApp);
        return new SsoClientConfig(
                EntandoAppHelper.determineRealm(entandoApp, ssoConnectionInfo),
                clientId,
                clientId).withRole("superuser").withPermission("realm-management", "realm-admin");
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public Optional<String> getQualifier() {
        return Optional.empty();
    }

}
