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

import static org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper.KEYCLOAK_REALM;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.client.EntandoOperatorTestConfig;
import org.entando.kubernetes.client.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.client.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.client.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.app.ComponentManagerDeployableContainer;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.TlsHelper;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.test.common.CommonLabels;
import org.entando.kubernetes.test.e2etest.helpers.EntandoAppE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.K8SIntegrationTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

abstract class AddEntandoAppBaseIT implements FluentIntegrationTesting, CommonLabels {

    protected final K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();
    protected final DefaultKubernetesClient client = helper.getClient();

    protected AddEntandoAppBaseIT() {
        EntandoOperatorConfig.getCertificateAuthoritySecretName()
                .ifPresent(s -> TlsHelper.trustCertificateAuthoritiesIn(helper.getClient().secrets().withName(s).get()));

    }

    @BeforeEach
    void cleanup() {
        helper.setTextFixture(
                deleteAll(EntandoDatabaseService.class).fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                        .deleteAll(EntandoApp.class).fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE));
        this.helper.externalDatabases().deletePgTestPod(EntandoAppE2ETestHelper.TEST_NAMESPACE);
        helper.keycloak().prepareDefaultKeycloakSecretAndConfigMap();
        helper.keycloak().deleteRealm(KEYCLOAK_REALM);
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            helper.entandoApps().listenAndRespondWithImageVersionUnderTest(EntandoAppE2ETestHelper.TEST_NAMESPACE);
        } else {
            EntandoAppController controller = new EntandoAppController(helper.getClient(), false);
            helper.entandoApps()
                    .listenAndRespondWithStartupEvent(EntandoAppE2ETestHelper.TEST_NAMESPACE, controller::onStartup);
        }
    }

    void createAndWaitForApp(EntandoApp entandoApp, int waitOffset, boolean deployingDbContainers) {
        this.helper.keycloak()
                .deleteKeycloakClients(entandoApp, "entando-web", EntandoAppE2ETestHelper.TEST_APP_NAME + "-de",
                        EntandoAppE2ETestHelper.TEST_APP_NAME + "-" + "server");
        this.helper.keycloak()
                .ensureKeycloakClient(entandoApp.getSpec(), EntandoAppE2ETestHelper.K8S_SVC_CLIENT_ID,
                        Collections.singletonList(KubeUtils.ENTANDO_APP_ROLE));
        this.helper.entandoApps().createAndWaitForApp(entandoApp, waitOffset, deployingDbContainers);
    }

    @AfterEach
    void afterwards() {
        helper.afterTest();
    }

    protected void verifyAllExpectedResources(EntandoApp entandoApp) {
        verifyEntandoDbDeployment();
        verifyEntandoServerDeployment();
        verifyEntandoDatabasePreparation(entandoApp);
        verifyKeycloakClientsCreation();
    }

    protected abstract void verifyEntandoDbDeployment();

    protected void verifyEntandoServerDeployment() {
        Deployment appServerDeployment = client.apps().deployments().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-server-deployment")
                .get();
        Container theEntandoAppContainer = theContainerNamed("server-container").on(appServerDeployment);
        assertThat(thePortNamed(SERVER_PORT).on(theEntandoAppContainer).getContainerPort(), is(8080));
        assertThat(theEntandoAppContainer.getImage(), containsString("entando-de-app-wildfly"));
        Deployment componentManagerDeployment = client.apps().deployments().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-cm-deployment")
                .get();
        Container theComponentManagerContainer = theContainerNamed("de-container").on(componentManagerDeployment);
        assertThat(theComponentManagerContainer.getImage(), containsString("entando-component-manager"));
        assertThat(thePortNamed("de-port").on(theComponentManagerContainer).getContainerPort(), is(8083));
        Deployment appBuilderDeployment = client.apps().deployments().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-ab-deployment")
                .get();
        Container theAppBuilderContainer = theContainerNamed("appbuilder-container")
                .on(appBuilderDeployment);
        assertThat(theAppBuilderContainer.getImage(), containsString("app-builder"));
        assertThat(thePortNamed("appbuilder-port").on(theAppBuilderContainer).getContainerPort(), is(8081));
        Service appServerService = client.services().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-server-service").get();
        assertThat(thePortNamed(SERVER_PORT).on(appServerService).getPort(), is(8080));

        Service componentManagerService = client.services().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-cm-service").get();
        assertThat(thePortNamed("de-port").on(componentManagerService).getPort(), is(8083));
        Service appBuilderService = client.services().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-ab-service").get();
        assertThat(thePortNamed("appbuilder-port").on(appBuilderService).getPort(), is(8081));
        assertTrue(appServerDeployment.getStatus().getReadyReplicas() >= 1);
        assertTrue(helper.entandoApps().getOperations()
                .inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME).fromServer()
                .get().getStatus().forServerQualifiedBy("server").isPresent());
        await().atMost(30, SECONDS).ignoreExceptions().until(() -> readPath("/app-builder/index.html").contains("App Builder"));
        await().atMost(30, SECONDS).ignoreExceptions().until(() -> readPath("/entando-de-app/index.jsp").contains("Entando - Welcome"));
        await().atMost(30, SECONDS).ignoreExceptions().until(() -> pathOk("/digital-exchange/actuator/health"));
    }

    private Boolean pathOk(String pathToTest) {
        return HttpTestHelper.statusOk(
                HttpTestHelper.getDefaultProtocol() + "://" + EntandoAppE2ETestHelper.TEST_APP_NAME + "."
                        + helper.getDomainSuffix()
                        + pathToTest);
    }

    private String readPath(String pathToTest) {
        return HttpTestHelper
                .read(HttpTestHelper.getDefaultProtocol() + "://" + EntandoAppE2ETestHelper.TEST_APP_NAME + "."
                        + helper.getDomainSuffix()
                        + pathToTest);
    }

    protected void verifyEntandoDatabasePreparation(EntandoApp entandoApp) {
        Pod entandoServerDbPreparationPod = client.pods().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withLabels(dbPreparationJobLabels(entandoApp, NameUtils.DEFAULT_SERVER_QUALIFIER))
                .list().getItems().get(0);
        assertThat(theInitContainerNamed(EntandoAppE2ETestHelper.TEST_APP_NAME + "-portdb-schema-creation-job")
                        .on(entandoServerDbPreparationPod).getImage(),
                containsString("entando-k8s-dbjob"));
        assertThat(theInitContainerNamed(EntandoAppE2ETestHelper.TEST_APP_NAME + "-servdb-schema-creation-job")
                        .on(entandoServerDbPreparationPod).getImage(),
                containsString("entando-k8s-dbjob"));
        assertThat(theInitContainerNamed(EntandoAppE2ETestHelper.TEST_APP_NAME + "-server-db-population-job")
                        .on(entandoServerDbPreparationPod).getImage(),
                containsString("entando-de-app-wildfly"));
        entandoServerDbPreparationPod.getStatus().getInitContainerStatuses()
                .forEach(containerStatus -> assertThat(containerStatus.getState().getTerminated().getExitCode(), is(0)));
        Pod componentManagerDbPreparationPod = client.pods().inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withLabels(dbPreparationJobLabels(entandoApp, "cm"))
                .list().getItems().get(0);
        assertThat(theInitContainerNamed(EntandoAppE2ETestHelper.TEST_APP_NAME + "-dedb-schema-creation-job")
                        .on(componentManagerDbPreparationPod).getImage(),
                containsString("entando-k8s-dbjob"));
        componentManagerDbPreparationPod.getStatus().getInitContainerStatuses()
                .forEach(containerStatus -> assertThat(containerStatus.getState().getTerminated().getExitCode(), is(0)));
    }

    protected void verifyKeycloakClientsCreation() {
        Optional<ClientRepresentation> serverClient = helper.keycloak()
                .findClientById(KEYCLOAK_REALM,
                        EntandoAppE2ETestHelper.TEST_APP_NAME + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER);
        assertTrue(serverClient.isPresent());
        String componentManagerClientId = EntandoAppE2ETestHelper.TEST_APP_NAME + "-"
                + ComponentManagerDeployableContainer.COMPONENT_MANAGER_QUALIFIER;
        List<RoleRepresentation> roles = helper.keycloak()
                .retrieveServiceAccountRoles(KEYCLOAK_REALM, componentManagerClientId, EntandoAppE2ETestHelper.K8S_SVC_CLIENT_ID);
        assertTrue(roles.stream().anyMatch(role -> role.getName().equals(KubeUtils.ENTANDO_APP_ROLE)));

    }

}
