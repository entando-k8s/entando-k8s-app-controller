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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.command.IngressingDeployCommand;
import org.entando.kubernetes.controller.support.controller.AbstractDbAwareController;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppSpec;

public class EntandoAppController extends AbstractDbAwareController<EntandoAppSpec, EntandoApp> {

    public static final String ENTANDO_K8S_SERVICE = "entando-k8s-service";

    @Inject
    public EntandoAppController(KubernetesClient kubernetesClient) {
        super(kubernetesClient);
    }

    public EntandoAppController(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient) {
        super(k8sClient, keycloakClient);
    }

    public EntandoAppController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoApp entandoApp) {
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources()
                .findKeycloak(entandoApp, entandoApp.getSpec()::getKeycloakToUse);
        DatabaseServiceResult databaseServiceResult = prepareDatabaseService(entandoApp, entandoApp.getSpec().getDbms().orElse(
                DbmsVendor.EMBEDDED));
        EntandoAppDeploymentResult entandoAppDeployment = performDeployCommand(
                new EntandoAppServerDeployable(entandoApp, keycloakConnectionConfig, databaseServiceResult)
        );
        performDeployCommand(new AppBuilderDeployable(entandoApp, keycloakConnectionConfig));
        EntandoK8SService k8sService = new EntandoK8SService(
                this.k8sClient.services().loadControllerService(EntandoAppController.ENTANDO_K8S_SERVICE));
        performDeployCommand(
                new ComponentManagerDeployable(entandoApp, keycloakConnectionConfig, k8sService, databaseServiceResult,
                        entandoAppDeployment)
        );
    }

    private EntandoAppDeploymentResult performDeployCommand(AbstractEntandoAppDeployable deployable) {
        EntandoAppDeploymentResult result = new IngressingDeployCommand<>(deployable).execute(k8sClient, keycloakClient);
        k8sClient.entandoResources().updateStatus(deployable.getCustomResource(), result.getStatus());
        return result;
    }

}
