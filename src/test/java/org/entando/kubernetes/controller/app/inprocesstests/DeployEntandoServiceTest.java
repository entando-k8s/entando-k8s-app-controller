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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimStatus;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressStatus;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Map;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.app.EntandoAppDeployableContainer;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.KeycloakClientConfigArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.controller.test.support.VariableReferenceAssertions;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.infrastructure.EntandoClusterInfrastructure;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tag("in-process")
public class DeployEntandoServiceTest implements InProcessTestUtil, FluentTraversals, VariableReferenceAssertions {

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
    private static final String APPBUILDER_PORT = "appbuilder-port";
    private static final String MY_APP_DB_SERVICE = MY_APP + "-db-service";
    private final EntandoApp entandoApp = new EntandoAppBuilder(newTestEntandoApp()).editSpec().withStandardServerImage(JeeServer.EAP)
            .endSpec().build();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();

    @Mock
    private SimpleKeycloakClient keycloakClient;
    @InjectMocks
    private EntandoAppController entandoAppController;

    @BeforeEach
    public void createReusedSecrets() {
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        entandoAppController = new EntandoAppController(client, keycloakClient);
        client.entandoResources().createOrPatchEntandoResource(entandoApp);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoApp.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoApp.getMetadata().getName());
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty(), "true");

    }

    @AfterEach
    public void removeJvmProps() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty());

    }

    @Test
    public void testPersistentVolumeClaim() {
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
        //With a default size of 2Gi
        assertThat(resultingPersistentVolumeClaim.getSpec().getResources().getRequests().get("storage").getAmount(),
                is("2"));
        assertThat(resultingPersistentVolumeClaim.getSpec().getResources().getRequests().get("storage").getFormat(),
                is("Gi"));
        //And labels that link this PVC to the EntandoApp JEE Server deployment
        assertThat(resultingPersistentVolumeClaim.getMetadata().getLabels().get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        assertThat(resultingPersistentVolumeClaim.getMetadata().getLabels().get(DEPLOYMENT_LABEL_NAME),
                is(MY_APP_SERVER));
        //And the PersistentVolumeClaim state was reloaded from  K8S
        verify(client.persistentVolumeClaims()).loadPersistentVolumeClaim(eq(newEntandoApp), eq(MY_APP_SERVER_PVC));
        //And K8S was instructed to update the status of the EntandoApp with the status of the PVC
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoApp), argThat(containsThePersistentVolumeClaimStatus(pvcStatus)));
    }

    @Test
    public void testService() {
        //Given I have an Entando App with a JBoss EAP server
        EntandoApp newEntandoApp = entandoApp;
        //And that K8S is up and receiving Service requests
        ServiceStatus serviceStatus = new ServiceStatus();
        lenient().when(client.services().loadService(eq(newEntandoApp), eq(MY_APP_SERVER_SERVICE)))
                .then(respondWithServiceStatus(serviceStatus));

        //When the the EntandoAppController is notified that a new EntandoApp has been added
        entandoAppController.onStartup(new StartupEvent());
        //Then a K8S Service was created with a name that reflects the EntandoApp and the fact that it is a JEE service
        NamedArgumentCaptor<Service> serviceCaptor = forResourceNamed(Service.class, MY_APP_SERVER_SERVICE);
        verify(client.services()).createOrReplaceService(eq(newEntandoApp), serviceCaptor.capture());
        Service resultingService = serviceCaptor.getValue();
        //And a selector that matches the EntandoApp and the EntandoAppJeeServer pods
        Map<String, String> selector = resultingService.getSpec().getSelector();
        assertThat(selector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_SERVER));
        assertThat(selector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        //And the TCP port 8080 named 'server-port'
        assertThat(thePortNamed(SERVER_PORT).on(resultingService).getPort(), is(8080));
        assertThat(thePortNamed(SERVER_PORT).on(resultingService).getProtocol(), is("TCP"));
        assertThat(thePortNamed(SERVER_PORT).on(resultingService).getTargetPort().getIntVal(), is(8080));
        //And the TCP port 8083 named 'de-port'
        assertThat(thePortNamed(DE_PORT).on(resultingService).getPort(), is(8083));
        assertThat(thePortNamed(DE_PORT).on(resultingService).getProtocol(), is("TCP"));
        assertThat(thePortNamed(DE_PORT).on(resultingService).getTargetPort().getIntVal(), is(8083));
        //And the Service state was reloaded from K8S
        verify(client.services()).loadService(eq(newEntandoApp), eq(MY_APP_SERVER_SERVICE));

        //And K8S was instructed to update the status of the EntandoApp with the status of the service
        verify(client.entandoResources(), atLeastOnce()).updateStatus(eq(newEntandoApp), argThat(matchesServiceStatus(serviceStatus)));
    }

    @Test
    public void testIngress() {
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
        Ingress theIngress = ingressArgumentCaptor.getValue();
        assertThat(theIngress.getSpec().getRules().get(0).getHost(), is("myapp.192.168.0.100.nip.io"));
        // Then a K8S Ingress Path was created that reflects the webcontext of the entando-de-app
        assertThat(theHttpPath(ENTANDO_DE_APP).on(theIngress).getBackend().getServicePort().getIntVal(), is(8080));
        assertThat(theHttpPath(ENTANDO_DE_APP).on(theIngress).getBackend().getServiceName(), is(MY_APP_SERVER_SERVICE));

        // And a K8S Ingress Path was created that reflects the webcontext of the component-manager
        assertThat(theHttpPath(DIGITAL_EXCHANGE).on(theIngress).getBackend().getServicePort().getIntVal(), is(8083));
        assertThat(theHttpPath(DIGITAL_EXCHANGE).on(theIngress).getBackend().getServiceName(), is(MY_APP_SERVER_SERVICE));

        // And a K8S Ingress Path was created that reflects the webcontext of the appbuilder
        assertThat(theHttpPath(APP_BUILDER).on(theIngress).getBackend().getServicePort().getIntVal(), is(8081));
        assertThat(theHttpPath(APP_BUILDER).on(theIngress).getBackend().getServiceName(), is(MY_APP_SERVER_SERVICE));

        //And the Ingress state was reloaded from K8S
        verify(client.ingresses(), atLeast(2))
                .loadIngress(eq(newEntandoApp.getMetadata().getNamespace()), eq(MY_APP + "-" + KubeUtils.DEFAULT_INGRESS_SUFFIX));
        //And K8S was instructed to update the status of the EntandoApp with the status of the ingress
        verify(client.entandoResources(), atLeastOnce()).updateStatus(eq(newEntandoApp), argThat(matchesIngressStatus(ingressStatus)));
    }

    @Test
    public void testDeployment() {
        //Given I use the 6.0.0 image version by default
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_DOCKER_IMAGE_VERSION_FALLBACK.getJvmSystemProperty(), "6.0.0");
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

        verifyTheAppBuilderContainer(theServerDeployment);
        verifyTheEntandoServerContainer(theServerDeployment);
        verifyTheComponentManagerContainer(theServerDeployment);

        //And mapping a persistent volume with a name that reflects the EntandoApp and the fact that this is a DB Volume
        assertThat(theVolumeNamed(MY_APP_SERVER_VOLUME).on(theServerDeployment).getPersistentVolumeClaim().getClaimName(),
                is(MY_APP_SERVER_PVC));

        //And the Deployment state was reloaded from K8S
        verify(client.deployments()).loadDeployment(eq(newEntandoApp), eq(MY_APP_SERVER_DEPLOYMENT));
        verify(client.entandoResources()).updatePhase(eq(newEntandoApp), eq(EntandoDeploymentPhase.SUCCESSFUL));
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
        assertThat(theComponentManagerContainer.getImage(), is("docker.io/entando/entando-component-manager:6.0.0"));
        //And that is configured to point to the DB Service
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_USERNAME").on(theComponentManagerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-dedb-secret"));
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_USERNAME").on(theComponentManagerContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.USERNAME_KEY));
        assertThat(theVariableNamed("SPRING_DATASOURCE_URL").on(theComponentManagerContainer),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_dedb"));
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_PASSWORD").on(theComponentManagerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-dedb-secret"));
        assertThat(theVariableReferenceNamed("SPRING_DATASOURCE_PASSWORD").on(theComponentManagerContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed("ENTANDO_URL").on(theComponentManagerContainer), is("http://localhost:8080/entando-de-app"));
        assertThat(theVariableNamed("DB_VENDOR").on(theComponentManagerContainer), is("mysql"));

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
        assertThat(theVariableNamed("REACT_APP_DOMAIN").on(theAppBuilderContainer), is("/entando-de-app"));
        //That points to the correct Docker image
        assertThat(theAppBuilderContainer.getImage(), is("docker.io/entando/app-builder:6.0.0"));
    }

    private void verifyTheEntandoServerContainer(Deployment theServerDeployment) {
        //With a container for the Entando server with a name that reflects the EntandoApp name and the fact that it is the servers's
        // container
        Container theEntandoServerContainer = theContainerNamed("server-container").on(theServerDeployment);
        //That points to the correct Docker image
        assertThat(theEntandoServerContainer.getImage(), is("docker.io/entando/entando-de-app-eap:6.0.0"));
        //Exposing a port named 'server-port' on 8080
        assertThat(thePortNamed(SERVER_PORT).on(theEntandoServerContainer).getContainerPort(), is(8080));
        assertThat(thePortNamed(SERVER_PORT).on(theEntandoServerContainer).getProtocol(), is(TCP));
        //And that is configured to point to the DB Service
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(theEntandoServerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-portdb-secret"));
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(theEntandoServerContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.USERNAME_KEY));
        assertThat(theVariableNamed("PORTDB_URL").on(theEntandoServerContainer),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_portdb"));
        assertThat(theVariableReferenceNamed("SERVDB_PASSWORD").on(theEntandoServerContainer).getSecretKeyRef().getName(),
                is(MY_APP + "-servdb-secret"));
        assertThat(theVariableReferenceNamed("SERVDB_PASSWORD").on(theEntandoServerContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed("SERVDB_URL").on(theEntandoServerContainer),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_servdb"));
        //But the db check on startup is disabled
        assertThat(theVariableNamed("DB_STARTUP_CHECK").on(theEntandoServerContainer), is("false"));
        //And Keycloak was configured to support OIDC Integration from the EntandoApp
        verify(keycloakClient).createPublicClient(eq(ENTANDO_KEYCLOAK_REALM), eq("https://myapp.192.168.0.100.nip.io"));
        //the controllers logged into Keycloak independently for the EntandoApp deployment
        verify(keycloakClient, atLeast(1))
                .login(eq(MY_KEYCLOAK_BASE_URL), eq("entando_keycloak_admin"), anyString());
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
                is(ENTANDO_DE_APP + EntandoAppDeployableContainer.HEALTH_CHECK_PATH));
        assertThat(theEntandoServerContainer.getReadinessProbe().getHttpGet().getPort().getIntVal(), is(8080));

    }
}
