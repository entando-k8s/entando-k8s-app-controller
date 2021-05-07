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

import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.JeeServer;
import org.entando.kubernetes.test.e2etest.common.SampleWriter;
import org.entando.kubernetes.test.e2etest.helpers.EntandoAppE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("post-deployment")})
class AddEntandoAppWithExternalPostgresqlDatabaseIT extends AddEntandoAppBaseIT {

    @Test
    void create() {
        helper.externalDatabases().prepareExternalPostgresqlDatabase(EntandoAppE2ETestHelper.TEST_NAMESPACE, "EntandoApp");
        EntandoApp entandoApp = new EntandoAppBuilder().withNewSpec()
                .withStandardServerImage(JeeServer.WILDFLY)
                .withDbms(DbmsVendor.POSTGRESQL)
                .withIngressHostName(EntandoAppE2ETestHelper.TEST_APP_NAME + "." + helper.getDomainSuffix())
                .withNewKeycloakToUse()
                .withRealm(KeycloakE2ETestHelper.KEYCLOAK_REALM)
                .endKeycloakToUse()
                .withReplicas(1)
                .withTlsSecretName(null)
                .endSpec()
                .build();
        entandoApp.setMetadata(new ObjectMeta());
        entandoApp.getMetadata().setNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE);
        SampleWriter.writeSample(entandoApp, "app-with-external-postgresql-db");
        entandoApp.getMetadata().setName(EntandoAppE2ETestHelper.TEST_APP_NAME);
        //When I create the entando app
        createAndWaitForApp(entandoApp, 0, false);
        //I see all the expected deployments
        verifyAllExpectedResources(entandoApp);
        //And recreating the app still succeeds even though all the DB secrets were deleted
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_FORCE_DB_PASSWORD_RESET.getJvmSystemProperty(), "true");
        helper.setTextFixture(deleteAll(EntandoApp.class).fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE));
        createAndWaitForApp(entandoApp, 0, false);
        verifyAllExpectedResources(entandoApp);

    }

    @Override
    @AfterEach
    void afterwards() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_FORCE_DB_PASSWORD_RESET.getJvmSystemProperty());
        super.afterwards();
    }

    @Override
    protected void verifyEntandoDbDeployment() {
        //Nothing to do here
    }
}

