/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.app.interprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.entando.kubernetes.controller.integrationtest.support.EntandoAppIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("post-deployment")})
class AddEntandoAppWithContainerizedDatabaseIT extends AddEntandoAppBaseIT {

    static final int ENTANDO_DB_PORT = 5432;

    @Test
    void create() {
        //When I create an EntandoApp and I specify it to use Wildfly and PostgreSQL
        EntandoApp entandoApp = new EntandoAppBuilder()
                .withNewSpec()
                .withStandardServerImage(JeeServer.WILDFLY)
                .withDbms(DbmsVendor.POSTGRESQL)
                .withNewKeycloakToUse()
                .withRealm(KeycloakIntegrationTestHelper.KEYCLOAK_REALM)
                .endKeycloakToUse()
                .withIngressHostName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "." + helper.getDomainSuffix())
                .withReplicas(1)
                .endSpec()
                .build();
        entandoApp.setMetadata(new ObjectMeta());
        entandoApp.getMetadata().setNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE);
        entandoApp.getMetadata().setName(EntandoAppIntegrationTestHelper.TEST_APP_NAME);
        SampleWriter.writeSample(entandoApp, "app-with-embedded-postgresql-db");


        createAndWaitForApp(entandoApp, 100, true);
        //Then I expect to see
        verifyAllExpectedResources();
    }

    @Override
    protected void verifyEntandoDbDeployment() {
        Deployment deployment = client.apps().deployments().inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-db-deployment").get();
        assertThat(
                deployment
                        .getSpec().getTemplate().getSpec().getContainers().get(0).getPorts().get(0).getContainerPort(),
                is(ENTANDO_DB_PORT));
        Service service = client.services().inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-db-service").get();
        assertThat(service.getSpec()
                .getPorts().get(0).getPort(), is(ENTANDO_DB_PORT));
        assertTrue(deployment.getStatus().getReadyReplicas() >= 1);
        assertTrue(helper.entandoApps().getOperations()
                .inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME).fromServer()
                .get().getStatus().forDbQualifiedBy("db").isPresent());
    }

}
