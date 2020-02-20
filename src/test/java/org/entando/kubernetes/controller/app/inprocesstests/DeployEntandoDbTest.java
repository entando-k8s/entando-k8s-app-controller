package org.entando.kubernetes.controller.app.inprocesstests;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.text.IsEmptyString.emptyOrNullString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Arrays;
import java.util.Map;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.LabeledArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("in-process")
//in execute component test
public class DeployEntandoDbTest implements InProcessTestUtil, FluentTraversals {

    private static final String MY_APP_PORTDB_SECRET = MY_APP + "-portdb-secret";
    private static final String MY_APP_SERVDB_SECRET = MY_APP + "-servdb-secret";
    private static final String MY_APP_DEDB_SECRET = MY_APP + "-dedb-secret";
    private static final String MY_APP_DB = MY_APP + "-db";
    private static final String MY_APP_DB_PVC = MY_APP_DB + "-pvc";
    private static final String MY_APP_DB_ADMIN_SECRET = MY_APP_DB + "-admin-secret";
    private static final String MY_APP_DB_SERVICE = MY_APP_DB + "-service";
    private static final String MY_APP_DB_DEPLOYMENT = MY_APP_DB + "-deployment";
    private static final String MY_APP_DB_VOLUME = MY_APP_DB + "-volume";
    private final EntandoApp entandoApp = newTestEntandoApp();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
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
    }

    @Test
    public void testSecrets() {
        //Given I have a fully deployed KeycloakServer
        client.secrets().overwriteControllerSecret(newKeycloakAdminSecret());
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = entandoApp;
        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());
        //Then a K8S Secret was created with a name that reflects the EntandoApp and the fact that it is a secret
        NamedArgumentCaptor<Secret> adminSecretCaptor = forResourceNamed(Secret.class, MY_APP_DB_ADMIN_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(entandoApp), adminSecretCaptor.capture());
        Secret adminSecret = adminSecretCaptor.getValue();
        assertThat(adminSecret.getStringData().get(KubeUtils.USERNAME_KEY), is("root"));
        assertThat(adminSecret.getStringData().get(KubeUtils.PASSSWORD_KEY), is(not(emptyOrNullString())));
        NamedArgumentCaptor<Secret> servSecretCaptor = forResourceNamed(Secret.class, MY_APP_SERVDB_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(entandoApp), servSecretCaptor.capture());
        Secret servSecret = servSecretCaptor.getValue();
        assertThat(servSecret.getStringData().get(KubeUtils.USERNAME_KEY), is("my_app_servdb"));
        assertThat(servSecret.getStringData().get(KubeUtils.PASSSWORD_KEY), is(not(emptyOrNullString())));
        NamedArgumentCaptor<Secret> portSecretCaptor = forResourceNamed(Secret.class, MY_APP_PORTDB_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(entandoApp), portSecretCaptor.capture());
        Secret portSecret = portSecretCaptor.getValue();
        assertThat(portSecret.getStringData().get(KubeUtils.USERNAME_KEY), is("my_app_portdb"));
        assertThat(portSecret.getStringData().get(KubeUtils.PASSSWORD_KEY), is(not(emptyOrNullString())));
        NamedArgumentCaptor<Secret> componentManagerSecretCaptor = forResourceNamed(Secret.class, MY_APP_DEDB_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(entandoApp), componentManagerSecretCaptor.capture());
        Secret componentManagerSecret = componentManagerSecretCaptor.getValue();
        assertThat(componentManagerSecret.getStringData().get(KubeUtils.USERNAME_KEY), is("my_app_dedb"));
        assertThat(componentManagerSecret.getStringData().get(KubeUtils.PASSSWORD_KEY), is(not(emptyOrNullString())));
    }

    @Test
    public void testPersistentVolumeClaim() {
        //Given I have a fully deployed KeycloakServer
        client.secrets().overwriteControllerSecret(newKeycloakAdminSecret());
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = entandoApp;
        //And that K8S is up and receiving PVC requests
        PersistentVolumeClaimStatus pvcStatus = new PersistentVolumeClaimStatus();
        lenient().when(client.persistentVolumeClaims().loadPersistentVolumeClaim(eq(newEntandoApp), eq(MY_APP_DB_PVC)))
                .then(respondWithPersistentVolumeClaimStatus(pvcStatus));
        //When the the EntandoAppController is notified that a new EntandoApp has been added
        entandoAppController.onStartup(new StartupEvent());

        //Then K8S was instructed to create a PersistentVolumeClaim
        NamedArgumentCaptor<PersistentVolumeClaim> pvcCaptor = forResourceNamed(PersistentVolumeClaim.class, MY_APP_DB_PVC);
        verify(client.persistentVolumeClaims()).createPersistentVolumeClaimIfAbsent(eq(newEntandoApp), pvcCaptor.capture());
        //With a name that reflects the EntandoApp and the fact that this is a DB claim
        PersistentVolumeClaim resultingPersistentVolumeClaim = pvcCaptor.getValue();
        assertThat(resultingPersistentVolumeClaim.getSpec().getAccessModes().get(0), is("ReadWriteOnce"));
        //With a default size of 2Gi
        assertThat(resultingPersistentVolumeClaim.getSpec().getResources().getRequests().get("storage").getAmount(),
                is("2"));
        assertThat(resultingPersistentVolumeClaim.getSpec().getResources().getRequests().get("storage").getFormat(),
                is("Gi"));
        //And labels that link this PVC to the EntandoAppd DB deployment
        assertThat(resultingPersistentVolumeClaim.getMetadata().getLabels().get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        assertThat(resultingPersistentVolumeClaim.getMetadata().getLabels().get(DEPLOYMENT_LABEL_NAME), is(MY_APP_DB));
        //And the PersistentVolumeClaim state was reloaded from  K8S
        verify(client.persistentVolumeClaims())
                .loadPersistentVolumeClaim(eq(newEntandoApp), eq(MY_APP_DB_PVC));
        //And K8S was instructed to update the status of the EntandoApp with the status of the PVC
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoApp), argThat(containsThePersistentVolumeClaimStatus(pvcStatus)));
    }

    @Test
    public void testService() {
        //Given I have a fully deployed KeycloakServer
        client.secrets().overwriteControllerSecret(newKeycloakAdminSecret());
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = this.entandoApp;
        //And K8S is receiving Service requests
        ServiceStatus serviceStatus = new ServiceStatus();
        lenient().when(client.services().loadService(eq(newEntandoApp), eq(MY_APP_DB_SERVICE)))
                .then(respondWithServiceStatus(serviceStatus));

        //When the the EntandoAppController is notified that a new EntandoApp has been added
        entandoAppController.onStartup(new StartupEvent());
        //Then a K8S Service was created with a name that reflects the EntandoApp and the fact that it is a DB service
        NamedArgumentCaptor<Service> serviceCaptor = forResourceNamed(Service.class, MY_APP_DB_SERVICE);
        verify(client.services()).createOrReplaceService(eq(newEntandoApp), serviceCaptor.capture());
        Service resultingService = serviceCaptor.getValue();
        //And a selector that matches the EntandoApp and the EntandoAppDB pods
        Map<String, String> selector = resultingService.getSpec().getSelector();
        assertThat(selector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_DB));
        assertThat(selector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        //And the TCP port 3306 named 'db-port'
        ServicePort port = resultingService.getSpec().getPorts().get(0);
        assertThat(port.getPort(), is(3306));
        assertThat(port.getProtocol(), is("TCP"));
        assertThat(port.getName(), is("db-port"));
        //And the Service state was reloaded from K8S
        verify(client.services()).loadService(eq(newEntandoApp), eq(MY_APP_DB_SERVICE));
        //And K8S was instructed to update the status of the EntandoApp with the status of the service
        verify(client.entandoResources(), atLeastOnce()).updateStatus(eq(newEntandoApp), argThat(matchesServiceStatus(serviceStatus)));
    }

    @Test
    public void testDeployment() {
        //Given I use the 6.0.0 image version by default
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_DOCKER_IMAGE_VERSION_FALLBACK.getJvmSystemProperty(), "6.0.0");

        //Given I have a fully deployed KeycloakServer
        client.secrets().overwriteControllerSecret(newKeycloakAdminSecret());
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = this.entandoApp;
        //And K8S is receiving Deployment requests
        DeploymentStatus deploymentStatus = new DeploymentStatus();
        lenient().when(client.deployments().loadDeployment(eq(newEntandoApp), eq(MY_APP_DB_DEPLOYMENT)))
                .then(respondWithDeploymentStatus(deploymentStatus));

        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());
        // Then a K8S deployment is created with a name that reflects the EntandoApp name and
        // the fact that it is a DB Deployment
        NamedArgumentCaptor<Deployment> deploymentCaptor = forResourceNamed(Deployment.class, MY_APP_DB_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), deploymentCaptor.capture());
        Deployment resultingDeployment = deploymentCaptor.getValue();
        //With a Pod Template that has labels linking it to the previously created K8S Service
        Map<String, String> selector = resultingDeployment.getSpec().getTemplate().getMetadata().getLabels();
        assertThat(selector.get(DEPLOYMENT_LABEL_NAME), is(MY_APP_DB));
        assertThat(selector.get(ENTANDO_APP_LABEL_NAME), is(MY_APP));
        //Exposing a port 3306
        Container theContainer = theContainerNamed("db-container").on(resultingDeployment);
        assertThat(thePortNamed(DB_PORT).on(theContainer).getContainerPort(), is(3306));
        assertThat(thePortNamed(DB_PORT).on(theContainer).getProtocol(), is(TCP));
        //And mapping a persistent volume with a name that reflects the EntandoApp and the fact that this is a DB Volume
        assertThat(theVolumeNamed(MY_APP_DB_VOLUME).on(resultingDeployment).getPersistentVolumeClaim().getClaimName(), is(MY_APP_DB_PVC));
        assertThat(theVolumeMountNamed(MY_APP_DB_VOLUME).on(thePrimaryContainerOn(resultingDeployment)).getMountPath(),
                is("/var/lib/mysql/data"));
        //That is linked to the previously created PersistentVolumeClaim
        assertThat(thePrimaryContainerOn(resultingDeployment).getImage(), is("docker.io/centos/mysql-57-centos7:latest"));
        //And the Deployment state was reloaded from K8S
        verify(client.deployments()).loadDeployment(eq(newEntandoApp), eq(MY_APP_DB_DEPLOYMENT));

        //And K8S was instructed to update the status of the EntandoApp with the status of the service
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoApp), argThat(matchesDeploymentStatus(deploymentStatus)));

        //And an appropriate readinessProbe was defined
        assertThat(thePrimaryContainerOn(resultingDeployment).getReadinessProbe().getExec(), is(not(nullValue())));
    }

    @Test
    public void testSchemaPreparation() {
        //Given I have a fully deployed KeycloakServer
        client.secrets().overwriteControllerSecret(newKeycloakAdminSecret());
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = this.entandoApp;
        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());
        //Then a Pod  is created that has labels linking it to the previously created EntandoApp
        LabeledArgumentCaptor<Pod> podCaptor = forResourceWithLabel(Pod.class, ENTANDO_APP_LABEL_NAME, MY_APP)
                .andWithLabel(KubeUtils.DB_JOB_LABEL_NAME, MY_APP + "-db-preparation-job");
        verify(client.pods()).runToCompletion(podCaptor.capture());
        Pod pod = podCaptor.getValue();
        verifySchemaCreationFor(MY_APP_PORTDB_SECRET, pod, MY_APP + "-portdb-schema-creation-job");
        verifySchemaCreationFor(MY_APP_SERVDB_SECRET, pod, MY_APP + "-servdb-schema-creation-job");
        verifySchemaCreationFor(MY_APP_DEDB_SECRET, pod, MY_APP + "-dedb-schema-creation-job");
        //And the DB Image is configured with the appropriate Environment Variables
        Container theDatabasePopulationJob = theInitContainerNamed(MY_APP + "-server-db-population-job").on(pod);
        assertThat(theDatabasePopulationJob.getCommand(),
                is(Arrays.asList("/bin/bash", "-c", "/entando-common/init-db-from-deployment.sh")));
        assertThat(theVariableNamed("PORTDB_URL").on(theDatabasePopulationJob),
                is("jdbc:mysql://" + MY_APP_DB_SERVICE + "." + MY_APP_NAMESPACE + ".svc.cluster.local:3306/my_app_portdb"));
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(theDatabasePopulationJob).getSecretKeyRef().getName(),
                is(MY_APP_PORTDB_SECRET));
        assertThat(theVariableReferenceNamed("PORTDB_PASSWORD").on(theDatabasePopulationJob).getSecretKeyRef().getName(),
                is(MY_APP_PORTDB_SECRET));
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(theDatabasePopulationJob).getSecretKeyRef().getKey(),
                is(KubeUtils.USERNAME_KEY));
        assertThat(theVariableReferenceNamed("PORTDB_PASSWORD").on(theDatabasePopulationJob).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));

    }

    private void verifySchemaCreationFor(String secretToMatch, Pod pod, String containerName) {
        Container resultingContainer = theInitContainerNamed(containerName).on(pod);
        //And the DB Schema preparation Image is configured with the appropriate Environment Variables
        assertThat(theVariableNamed(DATABASE_SCHEMA_COMMAND).on(resultingContainer), is("CREATE_SCHEMA"));
        assertThat(theVariableNamed(DATABASE_NAME).on(resultingContainer), is("my_app_db"));
        assertThat(theVariableNamed(DATABASE_SERVER_HOST).on(resultingContainer),
                is(MY_APP + "-db-service." + MY_APP_NAMESPACE + ".svc.cluster.local"));
        verifyStandardSchemaCreationVariables(MY_APP_DB_ADMIN_SECRET, secretToMatch, resultingContainer, DbmsVendor.MYSQL);
    }

}
