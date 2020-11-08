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

package org.entando.kubernetes.controller.app.inprocesstests;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Arrays;
import java.util.List;
import org.entando.kubernetes.controller.ExposedDeploymentResult;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.app.EntandoDatabaseConsumingContainer;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.spi.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.PublicIngressingDeployable;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppSpec;

public class EntandoDbConsumingDeployable implements PublicIngressingDeployable<ExposedDeploymentResult, EntandoAppSpec>,
        DbAwareDeployable {

    public static final String KEYCLOAK_PUBLIC_CLIENT_ID = "keycloak-public-client-id";
    public static final String TEST_INGRESS_NAMESPACE = "test-ingress-namespace";
    public static final String TEST_INGRESS_NAME = "test-ingress-name";
    public static final String ENTANDO_TEST_IMAGE_6_0_0 = "entando/test-image:6.0.0";
    private final KeycloakConnectionConfig keycloakConnectionConfig;
    private final EntandoApp entandoApp;
    private final List<DeployableContainer> containers;
    private DatabaseServiceResult databaseServiceResult;

    public EntandoDbConsumingDeployable(KeycloakConnectionConfig keycloakConnectionConfig, EntandoApp entandoApp,
            DatabaseServiceResult databaseServiceResult) {
        this.keycloakConnectionConfig = keycloakConnectionConfig;
        this.entandoApp = entandoApp;
        this.databaseServiceResult = databaseServiceResult;
        this.containers = Arrays.asList(new EntandoDatabaseConsumingContainer(entandoApp.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED)) {

            @Override
            public void addDatabaseConnectionVariables(List<EnvVar> envVars) {

            }

            @Override
            public String determineImageToUse() {
                return ENTANDO_TEST_IMAGE_6_0_0;
            }

            @Override
            public String getNameQualifier() {
                return "server";
            }

            @Override
            public int getPrimaryPort() {
                return 8080;
            }

        });
    }

    @Override
    public KeycloakConnectionConfig getKeycloakDeploymentResult() {
        return keycloakConnectionConfig;
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public DatabaseServiceResult getDatabaseServiceResult() {
        return databaseServiceResult;
    }

    @Override
    public String getIngressName() {
        return TEST_INGRESS_NAME;
    }

    @Override
    public String getIngressNamespace() {
        return TEST_INGRESS_NAMESPACE;
    }

    @Override
    public String getNameQualifier() {
        return "testserver";
    }

    @Override
    public EntandoApp getCustomResource() {
        return entandoApp;
    }

    @Override
    public ExposedDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new ExposedDeploymentResult(pod, service, ingress);
    }
}
