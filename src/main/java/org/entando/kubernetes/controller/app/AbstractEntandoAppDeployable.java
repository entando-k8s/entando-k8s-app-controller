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
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.deployable.PublicIngressingDeployable;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.common.EntandoResourceRequirements;
import org.entando.kubernetes.model.common.KeycloakToUse;

public abstract class AbstractEntandoAppDeployable implements PublicIngressingDeployable<EntandoAppDeploymentResult> {

    /**
     * The operating system level id of the default user in the Red Hat base images. Was determined to be 185 running the 'id' command in
     * the entando/entando-eap72-clusted-base image or entando/entando-wildfly17-base image or entando-component-manager image
     */
    public static final long DEFAULT_USERID_IN_JBOSS_BASE_IMAGES = 185L;

    protected final EntandoApp entandoApp;
    protected final SsoConnectionInfo keycloakConnectionConfig;

    protected AbstractEntandoAppDeployable(EntandoApp entandoApp, SsoConnectionInfo keycloakConnectionConfig) {
        this.entandoApp = entandoApp;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
    }

    @Override
    public Optional<KeycloakToUse> getPreferredKeycloakToUse() {
        return entandoApp.getSpec().getKeycloakToUse();
    }

    @Override
    public boolean isIngressRequired() {
        return true;
    }

    @Override
    public Optional<Long> getFileSystemUserAndGroupId() {
        return Optional.of(DEFAULT_USERID_IN_JBOSS_BASE_IMAGES);
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
    public SsoConnectionInfo getSsoConnectionInfo() {
        return keycloakConnectionConfig;
    }

    public Optional<String> getFileUploadLimit() {
        return entandoApp.getSpec().getResourceRequirements().flatMap(EntandoResourceRequirements::getFileUploadLimit);
    }

    @Override
    public Optional<String> getTlsSecretName() {
        return entandoApp.getSpec().getTlsSecretName();
    }

    @Override
    public Optional<String> getIngressHostName() {
        return entandoApp.getSpec().getIngressHostName();
    }

    @Override
    public String getServiceAccountToUse() {
        return getCustomResource().getSpec().getServiceAccountToUse().orElse(getDefaultServiceAccountName());
    }
}
