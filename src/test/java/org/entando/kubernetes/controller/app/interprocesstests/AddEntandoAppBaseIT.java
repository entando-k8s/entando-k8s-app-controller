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

package org.entando.kubernetes.controller.app.interprocesstests;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.app.ComponentManagerDeployableContainer;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.common.TlsHelper;
import org.entando.kubernetes.controller.integrationtest.support.ClusterInfrastructureIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.EntandoAppIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.HttpTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.K8SIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

public abstract class AddEntandoAppBaseIT implements FluentIntegrationTesting {

    protected final K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();
    protected final DefaultKubernetesClient client = helper.getClient();

    @BeforeEach
    public void cleanup() {
        helper.setTextFixture(
                deleteAll(EntandoDatabaseService.class).fromNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                        .deleteAll(EntandoApp.class).fromNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE));
        await().atMost(2, TimeUnit.MINUTES).ignoreExceptions().pollInterval(10, TimeUnit.SECONDS).until(this::killPgPod);
        registerListeners();
    }

    private boolean killPgPod() {
        PodResource<Pod, DoneablePod> resource = client.pods()
                .inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE).withName("pg-test");
        if (resource.fromServer().get() == null) {
            return true;
        }
        resource.delete();
        return false;
    }

    private void registerListeners() {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            helper.entandoApps().listenAndRespondWithImageVersionUnderTest(EntandoAppIntegrationTestHelper.TEST_NAMESPACE);
        } else {
            EntandoAppController controller = new EntandoAppController(helper.getClient(), false);
            helper.entandoApps()
                    .listenAndRespondWithStartupEvent(EntandoAppIntegrationTestHelper.TEST_NAMESPACE, controller::onStartup);
        }
        helper.keycloak()
                .listenAndRespondWithLatestImage(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE);
    }

    public void createAndWaitForApp(EntandoApp entandoApp, int waitOffset, boolean deployingDbContainers) {
        this.helper.keycloak().ensureKeycloak();
        this.helper.clusterInfrastructure().ensureInfrastructureSecret();
        this.helper.keycloak().deleteKeycloakClients("entando-web", EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-de",
                EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-" + "server");
        String k8sSvcClientId = ClusterInfrastructureIntegrationTestHelper.CLUSTER_INFRASTRUCTURE_NAME + "-k8s-svc";
        this.helper.keycloak().ensureKeycloakClient(k8sSvcClientId, Collections.singletonList(KubeUtils.ENTANDO_APP_ROLE));
        this.helper.entandoApps().createAndWaitForApp(entandoApp, waitOffset, deployingDbContainers);
    }

    @AfterEach
    public void afterwards() {
        helper.afterTest();
    }

    protected void verifyAllExpectedResources() {
        verifyEntandoDbDeployment();
        verifyEntandoServerDeployment();
        verifyEntandoDatabasePreparation();
        verifyKeycloakClientsCreation();
    }

    protected abstract void verifyEntandoDbDeployment();

    protected void verifyEntandoServerDeployment() {
        Deployment deployment = client.apps().deployments().inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-server-deployment")
                .get();
        Container theEntandoAppContainer = theContainerNamed("server-container").on(deployment);
        assertThat(thePortNamed(SERVER_PORT).on(theEntandoAppContainer).getContainerPort(), is(8080));
        assertThat(theEntandoAppContainer.getImage(), containsString("entando-de-app-wildfly"));
        Container theComponentManagerContainer = theContainerNamed("de-container").on(deployment);
        assertThat(theComponentManagerContainer.getImage(), containsString("entando-component-manager"));
        assertThat(thePortNamed("de-port").on(theComponentManagerContainer).getContainerPort(), is(8083));
        Container theAppBuilderContainer = theContainerNamed("appbuilder-container")
                .on(deployment);
        assertThat(theAppBuilderContainer.getImage(), containsString("app-builder"));
        assertThat(thePortNamed("appbuilder-port").on(theAppBuilderContainer).getContainerPort(), is(8081));
        Service service = client.services().inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-server-service").get();
        assertThat(thePortNamed(SERVER_PORT).on(service).getPort(), is(8080));
        assertThat(thePortNamed("de-port").on(service).getPort(), is(8083));
        assertThat(thePortNamed("appbuilder-port").on(service).getPort(), is(8081));
        assertTrue(deployment.getStatus().getReadyReplicas() >= 1);
        assertTrue(helper.entandoApps().getOperations()
                .inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME).fromServer()
                .get().getStatus().forServerQualifiedBy("server").isPresent());
        await().atMost(30, SECONDS).ignoreExceptions().until(() -> readPath("/app-builder/index.html").contains("Entando App Builder"));
        await().atMost(30, SECONDS).ignoreExceptions().until(() -> readPath("/entando-de-app/index.jsp").contains("Entando - Welcome"));
        await().atMost(30, SECONDS).ignoreExceptions().until(() -> pathOk("/digital-exchange/actuator/health"));
    }

    private Boolean pathOk(String pathToTest) {
        return HttpTestHelper.statusOk(
                TlsHelper.getDefaultProtocol() + "://" + EntandoAppIntegrationTestHelper.TEST_APP_NAME + "."
                        + helper.getDomainSuffix()
                        + pathToTest);
    }

    private String readPath(String pathToTest) {
        return HttpTestHelper
                .read(TlsHelper.getDefaultProtocol() + "://" + EntandoAppIntegrationTestHelper.TEST_APP_NAME + "."
                        + helper.getDomainSuffix()
                        + pathToTest);
    }

    protected void verifyEntandoDatabasePreparation() {
        Pod pod = client.pods().inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withLabel(KubeUtils.DB_JOB_LABEL_NAME, EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-db-preparation-job")
                .list().getItems().get(0);
        assertThat(theInitContainerNamed(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-portdb-schema-creation-job").on(pod).getImage(),
                containsString("entando-k8s-dbjob"));
        assertThat(theInitContainerNamed(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-servdb-schema-creation-job").on(pod).getImage(),
                containsString("entando-k8s-dbjob"));
        assertThat(theInitContainerNamed(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-dedb-schema-creation-job").on(pod).getImage(),
                containsString("entando-k8s-dbjob"));
        assertThat(theInitContainerNamed(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-server-db-population-job").on(pod).getImage(),
                containsString("entando-de-app-wildfly"));
        pod.getStatus().getInitContainerStatuses()
                .forEach(containerStatus -> assertThat(containerStatus.getState().getTerminated().getExitCode(), is(0)));
    }

    protected void verifyKeycloakClientsCreation() {
        Optional<ClientRepresentation> serverClient = helper.keycloak()
                .findClientById(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER);
        assertTrue(serverClient.isPresent());
        String componentManagerClientId = EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-"
                + ComponentManagerDeployableContainer.COMPONENT_MANAGER_QUALIFIER;
        String k8sSvcClientId = ClusterInfrastructureIntegrationTestHelper.CLUSTER_INFRASTRUCTURE_NAME + "-k8s-svc";
        List<RoleRepresentation> roles = helper.keycloak().retrieveServiceAccountRoles(componentManagerClientId, k8sSvcClientId);
        assertTrue(roles.stream().anyMatch(role -> role.getName().equals(KubeUtils.ENTANDO_APP_ROLE)));

    }

}
