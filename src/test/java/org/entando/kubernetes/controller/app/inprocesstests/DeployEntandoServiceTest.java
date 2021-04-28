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

package org.entando.kubernetes.controller.app.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimStatus;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressStatus;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Collections;
import java.util.Map;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.app.EntandoAppDeployableContainer;
import org.entando.kubernetes.controller.app.testutils.EnvVarAssertionHelper;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakClientConfig;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.client.doubles.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.support.client.doubles.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.entando.kubernetes.test.componenttest.InProcessTestUtil;
import org.entando.kubernetes.test.componenttest.argumentcaptors.KeycloakClientConfigArgumentCaptor;
import org.entando.kubernetes.test.componenttest.argumentcaptors.NamedArgumentCaptor;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("in-process"), @Tag("pre-deployment"), @Tag("component")})
//Because Sonar cannot detect custom matchers and captors
@SuppressWarnings("java:S6073")
class DeployEntandoServiceTest implements InProcessTestUtil, EnvVarAssertionHelper, VariableReferenceAssertions, FluentTraversals {

    private static final String MY_APP_SERVER = MY_APP + "-server";
    private static final String MY_APP_SERVER_SERVICE = MY_APP_SERVER + "-service";
    private static final String MY_APP_SERVER_PVC = MY_APP_SERVER + "-pvc";
    private static final String MY_APP_SERVER_VOLUME = MY_APP_SERVER + "-volume";
    private static final String ENTANDO_DE_APP = "/entando-de-app";
    private static final String SERVER_PORT = "server-port";
    private static final String DE_PORT = "de-port";
    private static final String DIGITAL_EXCHANGE = "/digital-exchange";
    private static final String APP_BUILDER = "/app-builder/";
    private static final String MY_APP_SERVER_DEPLOYMENT = MY_APP_SERVER + "-deployment";
    private static final String MY_APP_APP_BUILDER = MY_APP + "-ab";
    private static final String MY_APP_APP_BUILDER_SERVICE = MY_APP_APP_BUILDER + "-service";
    private static final String MY_APP_APP_BUILDER_DEPLOYMENT = MY_APP_APP_BUILDER + "-deployment";
    private static final String MY_APP_COMPONENT_MANAGER = MY_APP + "-cm";
    private static final String MY_APP_COMPONENT_MANAGER_SERVICE = MY_APP_COMPONENT_MANAGER + "-service";
    private static final String MY_APP_COMPONENT_MANAGER_DEPLOYMENT = MY_APP_COMPONENT_MANAGER + "-deployment";
    private static final String APPBUILDER_PORT = "appbuilder-port";
    private static final String MY_APP_DB_SERVICE = MY_APP + "-db-service";
    static final String MARKER_VAR_VALUE = "myvalue";
    static final String MARKER_VAR_NAME = "MARKER_VAR";
    private final EntandoApp entandoApp = new EntandoAppBuilder(newTestEntandoApp()).editSpec().withStandardServerImage(JeeServer.EAP)
            .withEnvironmentVariables(Collections.singletonList(new EnvVar(MARKER_VAR_NAME, MARKER_VAR_VALUE, null)))
            .withNewResourceRequirements()
            .withFileUploadLimit("500m")
            .withMemoryLimit("3Gi")
            .withCpuLimit("2000m")
            .withStorageRequest("4Gi")
            .endResourceRequirements()
            .withEcrGitSshSecretname("my-git-secret")
            .endSpec().build();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();

    @Mock
    private SimpleKeycloakClient keycloakClient;
    @InjectMocks
    private EntandoAppController entandoAppController;

    @BeforeEach
    void createReusedSecrets() {
        emulateKeycloakDeployment(client);
        entandoAppController = new EntandoAppController(client, keycloakClient);
        client.entandoResources().createOrPatchEntandoResource(entandoApp);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoApp.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoApp.getMetadata().getName());
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty(), "true");
        client.secrets()
                .createSecretIfAbsent(entandoApp, new SecretBuilder().withNewMetadata().withName("my-git-secret").endMetadata().build());
    }

    @AfterEach
    void removeJvmProps() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty());

    }

    @Test
    void testPersistentVolumeClaim() {
        //Given I have an Entando App with a JBoss EAP server
        EntandoApp newEntandoApp = entandoApp;
        //And that K8S is up and receiving PVC requests
        PersistentVolumeClaimStatus pvcStatus = new PersistentVolumeClaimStatus();
        lenient().when(client.persistentVolumeClaims()
                .loadPersistentVolumeClaim(eq(newEntandoApp), eq(MY_APP_SERVER_PVC)))
                .then(respondWithPersistentVolumeClaimStatus(pvcStatus));
        //When the EntandoAppController is notified that a new EntandoApp has been added
        entandoAppController.onStartup(new StartupEvent());

        //Then K8S was instructed to create a PersistentVolumeClaim for the JEE Server
        NamedArgumentCaptor<PersistentVolumeClaim> pvcCaptor = forResourceNamed(PersistentVolumeClaim.class, MY_APP_SERVER_PVC);
        verify(client.persistentVolumeClaims()).createPersistentVolumeClaimIfAbsent(eq(newEntandoApp), pvcCaptor.capture());
        //With a name that reflects the EntandoApp and the fact that this is a JEE Server claim
        PersistentVolumeClaim resultingPersistentVolumeClaim = pvcCaptor.getValue();
        assertThat(resultingPersistentVolumeClaim.getSpec().getAccessModes().get(0), is("ReadWriteOnce"));
        //With a request size of 4i
        assertThat(resultingPersistentVolumeClaim.getSpec().getResources().getRequests().get("storage").getAmount(),
                is("4"));
        assertThat(resultingPersistentVolumeClaim.getSpec().getResources().getRequests().get("storage").getFormat(),
                is("Gi"));
        //And labels that link this PVC to the EntandoApp JEE Server deployment
        assertThat(resultingPersistentVolumeClaim.getMetadata().getLabels().get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        assertThat(resultingPersistentVolumeClaim.getMetadata().getLabels().get(DEPLOYMENT_LABEL_NAME),
                is(MY_APP_SERVER));
        //And the PersistentVolumeClaim state was reloaded from  K8S
        verify(client.persistentVolumeClaims()).loadPersistentVolumeClaim(newEntandoApp, MY_APP_SERVER_PVC);
        //And K8S was instructed to update the status of the EntandoApp with the status of the PVC
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoApp), argThat(containsThePersistentVolumeClaimStatus(pvcStatus)));
    }

    @Test
    void testService() {
        //Given I have an Entando App with a JBoss EAP server
        EntandoApp newEntandoApp = entandoApp;
        //And that K8S is up and receiving Service requests
        ServiceStatus appServiceStatus = new ServiceStatus();
        lenient().when(client.services().loadService(eq(newEntandoApp), eq(MY_APP_SERVER_SERVICE)))
                .then(respondWithServiceStatus(appServiceStatus));

        //When the the EntandoAppController is notified that a new EntandoApp has been added
        entandoAppController.onStartup(new StartupEvent());
        //Then a K8S Service was created with a name that reflects the EntandoApp and the fact that it is the EntandoApp server
        NamedArgumentCaptor<Service> appServiceCaptor = forResourceNamed(Service.class, MY_APP_SERVER_SERVICE);
        verify(client.services()).createOrReplaceService(eq(newEntandoApp), appServiceCaptor.capture());
        Service appServerService = appServiceCaptor.getValue();
        //And a selector that matches the EntandoApp and the EntandoAppJeeServer pods
        Map<String, String> selector = appServerService.getSpec().getSelector();
        assertThat(selector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_SERVER));
        assertThat(selector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        //And the TCP port 8080 named 'server-port'
        assertThat(thePortNamed(SERVER_PORT).on(appServerService).getPort(), is(8080));
        assertThat(thePortNamed(SERVER_PORT).on(appServerService).getProtocol(), is("TCP"));
        assertThat(thePortNamed(SERVER_PORT).on(appServerService).getTargetPort().getIntVal(), is(8080));
        //Then a K8S Service was created with a name that reflects the EntandoApp and the fact that it is the AppBuilder service
        NamedArgumentCaptor<Service> appBuilderServiceCaptor = forResourceNamed(Service.class, MY_APP_APP_BUILDER_SERVICE);
        verify(client.services()).createOrReplaceService(eq(newEntandoApp), appBuilderServiceCaptor.capture());
        Service appBuilderService = appBuilderServiceCaptor.getValue();
        //And a selector that matches the EntandoApp and the AppBuilder pods
        Map<String, String> appBuilderSelector = appBuilderService.getSpec().getSelector();
        assertThat(appBuilderSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_APP_BUILDER));
        assertThat(appBuilderSelector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        //And the TCP port 8083 named 'ab-port'
        assertThat(thePortNamed(APPBUILDER_PORT).on(appBuilderService).getPort(), is(8081));
        assertThat(thePortNamed(APPBUILDER_PORT).on(appBuilderService).getProtocol(), is("TCP"));
        assertThat(thePortNamed(APPBUILDER_PORT).on(appBuilderService).getTargetPort().getIntVal(), is(8081));
        //Then a K8S Service was created with a name that reflects the EntandoApp and the fact that it is the ComponentManager service
        NamedArgumentCaptor<Service> componentManagerServiceCaptor = forResourceNamed(Service.class, MY_APP_COMPONENT_MANAGER_SERVICE);
        verify(client.services()).createOrReplaceService(eq(newEntandoApp), componentManagerServiceCaptor.capture());
        Service componentManagerService = componentManagerServiceCaptor.getValue();
        //And a selector that matches the EntandoApp and the ComponentManager pods
        Map<String, String> componentManagerSelector = componentManagerService.getSpec().getSelector();
        assertThat(componentManagerSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_COMPONENT_MANAGER));
        assertThat(componentManagerSelector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        //And the TCP port 8083 named 'de-port'
        assertThat(thePortNamed(DE_PORT).on(componentManagerService).getPort(), is(8083));
        assertThat(thePortNamed(DE_PORT).on(componentManagerService).getProtocol(), is("TCP"));
        assertThat(thePortNamed(DE_PORT).on(componentManagerService).getTargetPort().getIntVal(), is(8083));
        //And the Service state was reloaded from K8S
        verify(client.services()).loadService(newEntandoApp, MY_APP_SERVER_SERVICE);

        //And K8S was instructed to update the status of the EntandoApp with the status of the service
        verify(client.entandoResources(), atLeastOnce()).updateStatus(eq(newEntandoApp), argThat(matchesServiceStatus(appServiceStatus)));
    }

    @Test
    void testIngress() {
        //Given I have an Entando App with a JBoss EAP server
        EntandoApp newEntandoApp = entandoApp;
        //And that K8S is up and receiving Ingress requests
        IngressStatus ingressStatus = new IngressStatus();
        when(client.ingresses().loadIngress(eq(newEntandoApp.getMetadata().getNamespace()), any(String.class)))
                .then(respondWithIngressStatusForPath(ingressStatus, ENTANDO_DE_APP));

        //When the the EntandoAppController is notified that a new EntandoApp has been added
        entandoAppController.onStartup(new StartupEvent());
        //Then a K8S Ingress was created with the hostname specified in the Entando App
        ArgumentCaptor<Ingress> ingressArgumentCaptor = ArgumentCaptor.forClass(Ingress.class);
        verify(client.ingresses()).createIngress(eq(newEntandoApp), ingressArgumentCaptor.capture());
        Ingress theIngress = client.ingresses().loadIngress(ingressArgumentCaptor.getValue().getMetadata().getNamespace(),
                ingressArgumentCaptor.getValue().getMetadata().getName());
        assertThat(theIngress.getSpec().getRules().get(0).getHost(), is("myapp.192.168.0.100.nip.io"));
        // Then a K8S Ingress Path was created that reflects the webcontext of the entando-de-app
        assertThat(theHttpPath(ENTANDO_DE_APP).on(theIngress).getBackend().getServicePort().getIntVal(), is(8080));
        assertThat(theHttpPath(ENTANDO_DE_APP).on(theIngress).getBackend().getServiceName(), is(MY_APP_SERVER_SERVICE));

        // And a K8S Ingress Path was created that reflects the webcontext of the component-manager
        assertThat(theHttpPath(DIGITAL_EXCHANGE).on(theIngress).getBackend().getServicePort().getIntVal(), is(8083));
        assertThat(theHttpPath(DIGITAL_EXCHANGE).on(theIngress).getBackend().getServiceName(), is(MY_APP_COMPONENT_MANAGER_SERVICE));

        // And a K8S Ingress Path was created that reflects the webcontext of the appbuilder
        assertThat(theHttpPath(APP_BUILDER).on(theIngress).getBackend().getServicePort().getIntVal(), is(8081));
        assertThat(theHttpPath(APP_BUILDER).on(theIngress).getBackend().getServiceName(), is(MY_APP_APP_BUILDER_SERVICE));
        assertThat(theIngress.getMetadata().getAnnotations().get("nginx.ingress.kubernetes.io/proxy-body-size"), is("500m"));

        //And the Ingress state was reloaded from K8S
        verify(client.ingresses(), atLeast(2))
                .loadIngress(newEntandoApp.getMetadata().getNamespace(), MY_APP + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX);
        //And K8S was instructed to update the status of the EntandoApp with the status of the ingress
        verify(client.entandoResources(), atLeastOnce()).updateStatus(eq(newEntandoApp), argThat(matchesIngressStatus(ingressStatus)));
    }

    @Test
    void testDeployment() {
        //Given I have an Entando App with a JBoss EAP server
        EntandoApp newEntandoApp = entandoApp;
        //And K8S is receiving Deployment requests
        DeploymentStatus deploymentStatus = new DeploymentStatus();
        //And K8S is receiving Deployment requests
        lenient().when(client.deployments().loadDeployment(eq(newEntandoApp), eq(MY_APP_SERVER_DEPLOYMENT)))
                .then(respondWithDeploymentStatus(deploymentStatus));
        //And Keycloak is receiving requests
        when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);

        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());

        // Then a K8S deployment is created with a name that reflects the EntandoApp name and
        // the fact that it is a Server Deployment
        NamedArgumentCaptor<Deployment> deploymentCaptor = forResourceNamed(Deployment.class, MY_APP_SERVER_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), deploymentCaptor.capture());
        Deployment theServerDeployment = deploymentCaptor.getValue();

        //With a Pod Template that has labels linking it to the previously created K8S Service
        Map<String, String> selector = theServerDeployment.getSpec().getTemplate().getMetadata().getLabels();
        assertThat(selector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_SERVER));
        assertThat(selector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));

        verifyTheEntandoServerContainer(theServerDeployment);

        // Then a K8S deployment is created with a name that reflects the EntandoApp name and
        // the fact that it is an AppBuilder deployment
        NamedArgumentCaptor<Deployment> appBuilderDeploymentCaptor = forResourceNamed(Deployment.class, MY_APP_APP_BUILDER_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), appBuilderDeploymentCaptor.capture());
        Deployment appBuilderDeployment = appBuilderDeploymentCaptor.getValue();

        //With a Pod Template that has labels linking it to the previously created K8S Service
        Map<String, String> appBuilderSelector = appBuilderDeployment.getSpec().getTemplate().getMetadata().getLabels();
        assertThat(appBuilderSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_APP_BUILDER));
        assertThat(appBuilderSelector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        verifyTheAppBuilderContainer(appBuilderDeployment);

        // Then a K8S deployment is created with a name that reflects the EntandoApp name and
        // the fact that it is a ComponentManager  deployment
        NamedArgumentCaptor<Deployment> componentManagerDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_APP_COMPONENT_MANAGER_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), componentManagerDeploymentCaptor.capture());
        Deployment componentManagerDeployment = componentManagerDeploymentCaptor.getValue();

        //With a Pod Template that has labels linking it to the previously created K8S Service
        Map<String, String> componentManagerSelector = componentManagerDeployment.getSpec().getTemplate().getMetadata().getLabels();
        assertThat(componentManagerSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_COMPONENT_MANAGER));
        assertThat(componentManagerSelector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));

        verifyTheComponentManagerContainer(componentManagerDeployment);

        //And mapping a persistent volume with a name that reflects the EntandoApp and the fact that this is a DB Volume
        assertThat(theVolumeNamed(MY_APP_SERVER_VOLUME).on(theServerDeployment).getPersistentVolumeClaim().getClaimName(),
                is(MY_APP_SERVER_PVC));

        //And the Deployment state was reloaded from K8S
        verify(client.deployments()).loadDeployment(newEntandoApp, MY_APP_SERVER_DEPLOYMENT);
        verify(client.entandoResources()).updatePhase(newEntandoApp, EntandoDeploymentPhase.SUCCESSFUL);
        //And K8S was instructed to update the status of the EntandoApp with the status of the service
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoApp), argThat(matchesDeploymentStatus(deploymentStatus)));

        //And all volumes have been mapped
        verifyThatAllVolumesAreMapped(newEntandoApp, client, theServerDeployment);
        verifyThatAllVariablesAreMapped(newEntandoApp, client, theServerDeployment);

        assertThat(theServerDeployment.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup(), is(185L));

    }

    private void verifyTheComponentManagerContainer(Deployment theServerDeployment) {
        //With a container for the ComponentManagement server with a name that reflects the EntandoApp name and the fact that it is the
        // componentManager's container
        Container theComponentManagerContainer = theContainerNamed("de-container").on(theServerDeployment);
        //Exposing a port named 'de-port' on 8083
        assertThat(thePortNamed(DE_PORT).on(theComponentManagerContainer).getContainerPort(), is(8083));
        assertThat(thePortNamed(DE_PORT).on(theComponentManagerContainer).getProtocol(), is(TCP));
        //That points to the correct Docker image
        assertThat(theComponentManagerContainer.getImage(), Matchers.containsString("entando/entando-component-manager"));
        //And that is configured to point to the DB Service
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_USERNAME").on(theComponentManagerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-dedb-secret"));
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_USERNAME").on(theComponentManagerContainer).getSecretKeyRef().getKey(),
                is(SecretUtils.USERNAME_KEY));
        assertThat(theVariableNamed("SPRING_DATASOURCE_URL").on(theComponentManagerContainer),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_dedb"));
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_PASSWORD").on(theComponentManagerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-dedb-secret"));
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_PASSWORD").on(theComponentManagerContainer).getSecretKeyRef().getKey(),
                is(SecretUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed("ENTANDO_URL").on(theComponentManagerContainer),
                is("http://my-app-server-service.my-app-namespace.svc.cluster.local:8080/entando-de-app"));
        assertThat(theVariableNamed(MARKER_VAR_NAME).on(theComponentManagerContainer), is(MARKER_VAR_VALUE));
        assertThat(theVariableNamed("GIT_SSH_COMMAND").on(theComponentManagerContainer),
                is("ssh -o UserKnownHostsFile=/opt/.ssh/known_hosts -i /opt/.ssh/id_rsa -o IdentitiesOnly=yes"));

        KeycloakClientConfigArgumentCaptor keycloakClientConfigCaptor = forClientId(MY_APP + "-de");
        verify(keycloakClient).prepareClientAndReturnSecret(keycloakClientConfigCaptor.capture());
        KeycloakClientConfig keycloakClientConfig = keycloakClientConfigCaptor.getValue();
        assertThat(keycloakClientConfig.getClientId(), is(MY_APP + "-de"));
        assertThat(keycloakClientConfig.getRealm(), is("entando"));
        assertThat(keycloakClientConfig.getPermissions().get(0).getClientId(), is(MY_APP_SERVER));
        assertThat(keycloakClientConfig.getPermissions().get(0).getRole(), is("superuser"));
        assertThat(keycloakClientConfig.getRedirectUris().get(0), is("https://myapp.192.168.0.100.nip.io/digital-exchange/*"));
        verifySpringSecuritySettings(theComponentManagerContainer, MY_APP + "-de-secret");
        verifyKeycloakSettings(theComponentManagerContainer, MY_APP + "-de-secret");

    }

    private void verifyTheAppBuilderContainer(Deployment theServerDeployment) {
        //With a container for AppBuilder with a name that reflects the EntandoApp name and the fact that it is the AppBuilder's container
        Container theAppBuilderContainer = theContainerNamed("appbuilder-container").on(theServerDeployment);
        //That exposes a port named 'appbuilder-port' on 8081
        assertThat(thePortNamed(APPBUILDER_PORT).on(theAppBuilderContainer).getContainerPort(), is(8081));
        assertThat(thePortNamed(APPBUILDER_PORT).on(theAppBuilderContainer).getProtocol(), is(TCP));
        assertThat(theVariableNamed("DOMAIN").on(theAppBuilderContainer), is("/entando-de-app"));
        assertThat(theVariableNamed(MARKER_VAR_NAME).on(theAppBuilderContainer), is(MARKER_VAR_VALUE));
        //That points to the correct Docker image
        assertThat(theAppBuilderContainer.getImage(), Matchers.containsString("entando/app-builder"));
        Quantity memoryRequest = theAppBuilderContainer.getResources().getRequests().get("memory");
        assertThat(memoryRequest.getAmount(), is("128"));
        assertThat(memoryRequest.getFormat(), is("Mi"));
        Quantity cpuRequest = theAppBuilderContainer.getResources().getRequests().get("cpu");
        assertThat(cpuRequest.getAmount(), is("125"));
        assertThat(cpuRequest.getFormat(), is("m"));
        Quantity memoryLimit = theAppBuilderContainer.getResources().getLimits().get("memory");
        assertThat(memoryLimit.getAmount(), is("512"));
        assertThat(memoryLimit.getFormat(), is("Mi"));
        Quantity cpuLimit = theAppBuilderContainer.getResources().getLimits().get("cpu");
        assertThat(cpuLimit.getAmount(), is("500"));
        assertThat(cpuLimit.getFormat(), is("m"));

    }

    private void verifyTheEntandoServerContainer(Deployment theServerDeployment) {
        //With a container for the Entando server with a name that reflects the EntandoApp name and the fact that it is the servers's
        // container
        Container theEntandoServerContainer = theContainerNamed("server-container").on(theServerDeployment);
        //That points to the correct Docker image
        assertThat(theEntandoServerContainer.getImage(), Matchers.containsString("entando/entando-de-app-eap"));
        //Exposing a port named 'server-port' on 8080
        assertThat(thePortNamed(SERVER_PORT).on(theEntandoServerContainer).getContainerPort(), is(8080));
        assertThat(thePortNamed(SERVER_PORT).on(theEntandoServerContainer).getProtocol(), is(TCP));
        assertThat(thePortNamed("ping").on(theEntandoServerContainer).getContainerPort(), is(8888));
        //And that is configured to point to the DB Service
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(theEntandoServerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-portdb-secret"));
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(theEntandoServerContainer).getSecretKeyRef().getKey(),
                is(SecretUtils.USERNAME_KEY));
        assertThat(theVariableReferenceNamed("SERVDB_PASSWORD").on(theEntandoServerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-servdb-secret"));
        assertThat(theVariableReferenceNamed("SERVDB_PASSWORD").on(theEntandoServerContainer).getSecretKeyRef().getKey(),
                is(SecretUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed(MARKER_VAR_NAME).on(theEntandoServerContainer), is(MARKER_VAR_VALUE));
        assertThat(theVariableNamed("JGROUPS_CLUSTER_PASSWORD").on(theEntandoServerContainer), is(notNullValue()));
        assertThat(theVariableNamed("OPENSHIFT_KUBE_PING_NAMESPACE").on(theEntandoServerContainer), is(MY_APP_NAMESPACE));
        assertThat(theVariableNamed("OPENSHIFT_KUBE_PING_LABELS").on(theEntandoServerContainer),
                is(KubeUtils.DEPLOYMENT_LABEL_NAME + "=" + entandoApp.getMetadata().getName() + "-"
                        + NameUtils.DEFAULT_SERVER_QUALIFIER));

        //And per schema env vars are injected

        assertThat(theVariableNamed("PORTDB_URL").on(theEntandoServerContainer),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_portdb"));
        assertConnectionValidation(theEntandoServerContainer, "PORTDB_");

        assertThat(theVariableNamed("SERVDB_URL").on(theEntandoServerContainer),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_servdb"));
        assertConnectionValidation(theEntandoServerContainer, "SERVDB_");

        //But the db check on startup is disabled
        assertThat(theVariableNamed("DB_STARTUP_CHECK").on(theEntandoServerContainer), is("false"));
        //And Keycloak was configured to support OIDC Integration from the EntandoApp
        verify(keycloakClient, times(3))
                .createPublicClient(ENTANDO_KEYCLOAK_REALM, ENTANDO_PUBLIC_CLIENT, "https://myapp.192.168.0.100.nip.io");
        //the controllers logged into Keycloak independently for the EntandoApp deployment
        verify(keycloakClient, atLeast(1))
                .login(eq(InProcessTestUtil.MY_KEYCLOAK_BASE_URL), eq("entando_keycloak_admin"), anyString());
        KeycloakClientConfigArgumentCaptor keycloakClientConfigCaptor = forClientId(MY_APP_SERVER);
        verify(keycloakClient).prepareClientAndReturnSecret(keycloakClientConfigCaptor.capture());
        assertThat(keycloakClientConfigCaptor.getValue().getClientId(), is(MY_APP_SERVER));
        assertThat(keycloakClientConfigCaptor.getValue().getRealm(), is("entando"));
        assertThat(keycloakClientConfigCaptor.getValue().getPermissions().get(0).getRole(), is("realm-admin"));
        assertThat(keycloakClientConfigCaptor.getValue().getRedirectUris().get(0),
                is("https://myapp.192.168.0.100.nip.io/entando-de-app/*"));
        assertThat(keycloakClientConfigCaptor.getValue().getRedirectUris().get(1),
                is("http://myapp.192.168.0.100.nip.io/entando-de-app/*"));

        //And is configured to use the previously installed Keycloak instance
        verifyKeycloakSettings(theEntandoServerContainer, MY_APP_SERVER + "-secret");
        assertThat(theVariableNamed("SERVER_SERVLET_CONTEXT_PATH").on(theEntandoServerContainer), is(ENTANDO_DE_APP));

        // and the previously created Volume is mounted at /entando-data
        assertThat(theVolumeMountNamed(MY_APP_SERVER_VOLUME).on(theEntandoServerContainer).getMountPath(), is("/entando-data"));

        //And an appropriate readinessProbe was defined
        assertThat(theEntandoServerContainer.getReadinessProbe().getHttpGet().getPath(),
                is(ENTANDO_DE_APP + EntandoAppDeployableContainer.HEALTH_CHECK));
        assertThat(theEntandoServerContainer.getReadinessProbe().getHttpGet().getPort().getIntVal(), is(8080));
        //And the correct resource requests and limits have been applied
        Quantity memoryRequest = theEntandoServerContainer.getResources().getRequests().get("memory");
        assertThat(memoryRequest.getAmount(), is("750"));
        assertThat(memoryRequest.getFormat(), is("Mi"));
        Quantity cpuRequest = theEntandoServerContainer.getResources().getRequests().get("cpu");
        assertThat(cpuRequest.getAmount(), is("500"));
        assertThat(cpuRequest.getFormat(), is("m"));
        Quantity memoryLimit = theEntandoServerContainer.getResources().getLimits().get("memory");
        assertThat(memoryLimit.getAmount(), is("3"));
        assertThat(memoryLimit.getFormat(), is("Gi"));
        Quantity cpuLimit = theEntandoServerContainer.getResources().getLimits().get("cpu");
        assertThat(cpuLimit.getAmount(), is("2000"));
        assertThat(cpuLimit.getFormat(), is("m"));

    }
}
