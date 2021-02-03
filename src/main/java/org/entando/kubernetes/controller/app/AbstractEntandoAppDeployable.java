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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.deployable.PublicIngressingDeployable;
import org.entando.kubernetes.controller.support.spibase.PublicIngressingDeployableBase;
import org.entando.kubernetes.model.app.EntandoApp;

public abstract class AbstractEntandoAppDeployable
        implements PublicIngressingDeployable<EntandoAppDeploymentResult>, PublicIngressingDeployableBase<EntandoAppDeploymentResult> {

    protected final EntandoApp entandoApp;
    protected final KeycloakConnectionConfig keycloakConnectionConfig;

    protected AbstractEntandoAppDeployable(EntandoApp entandoApp, KeycloakConnectionConfig keycloakConnectionConfig) {
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
    }

    @Override
    public int getReplicas() {
        return entandoApp.getSpec().getReplicas().orElse(1);
    }

    @Override
    public EntandoApp getCustomResource() {
        return entandoApp;
    }

    @Override
    public EntandoAppDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new EntandoAppDeploymentResult(pod, service, ingress);
    }

    @Override
    public String getIngressName() {
        return NameUtils.standardIngressName(entandoApp);
    }

    @Override
    public String getIngressNamespace() {
        return entandoApp.getMetadata().getNamespace();
    }

    @Override
    public KeycloakConnectionConfig getKeycloakConnectionConfig() {
        return keycloakConnectionConfig;
    }

    @Override
    public String getServiceAccountToUse() {
        return getCustomResource().getSpec().getServiceAccountToUse().orElse(getDefaultServiceAccountName());
    }
}
