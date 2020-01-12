package org.entando.kubernetes.controller.app.inprocesstests;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.common.CreateExternalServiceCommand;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.LabeledArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceSpec;
import org.entando.kubernetes.model.infrastructure.EntandoClusterInfrastructure;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tag("in-process")
public class DeployEntandoOnExternalDbTest implements InProcessTestUtil, FluentTraversals {

    private static final String MY_APP_SERVDB_SECRET = MY_APP + "-servdb-secret";
    private static final String MY_APP_PORTDB_SECRET = MY_APP + "-portdb-secret";
    private final EntandoApp entandoApp = newTestEntandoApp();
    private final EntandoDatabaseService externalDatabase = buildExternalDatabase();
    private final EntandoKeycloakServer keycloakServer = newEntandoKeycloakServer();
    private final EntandoClusterInfrastructure entandoClusterInfrastructure = newEntandoClusterInfrastructure();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    @InjectMocks
    private EntandoAppController entandoAppController;

    @BeforeEach
    public void createCustomResources() {
        client.entandoResources().putEntandoCustomResource(externalDatabase);
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        entandoAppController = new EntandoAppController(client, keycloakClient);
        client.entandoResources().putEntandoCustomResource(entandoApp);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoApp.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoApp.getMetadata().getName());
    }

    @Test
    public void testSecrets() {
        //Given I have created an ExternalDatabase custom resource
        new CreateExternalServiceCommand(externalDatabase).execute(client);
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = entandoApp;
        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());
        //Then a K8S Secret was created with a name that reflects the EntandoApp and the fact that it is a secret
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
    }

    @Test
    public void testDeployment() {
        //Given I have created an ExternalDatabase custom resource
        new CreateExternalServiceCommand(externalDatabase).execute(client);
        //and I have an Entando App with a Wildfly server
        EntandoApp newEntandoApp = entandoApp;
        //And Keycloak is receiving requests
        when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);
        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());

        //Then a K8S deployment is created
        NamedArgumentCaptor<Deployment> entandoDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_APP + "-server-deployment");
        verify(client.deployments()).createDeployment(eq(newEntandoApp), entandoDeploymentCaptor.capture());
        Deployment entandoDeployment = entandoDeploymentCaptor.getValue();
        // And Entando is configured to point to the external DB Service using a username
        // that reflects the serv and port schemas
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getName(),
                is(MY_APP_PORTDB_SECRET));
        assertThat(theVariableReferenceNamed("PORTDB_USERNAME").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is(KubeUtils.USERNAME_KEY));
        assertThat(theVariableReferenceNamed("PORTDB_PASSWORD").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed("PORTDB_URL").on(thePrimaryContainerOn(entandoDeployment)),
                is("jdbc:oracle:thin:@//mydb-service." + MY_APP_NAMESPACE + ".svc.cluster.local:1521/my_db"));
        assertThat(theVariableReferenceNamed("SERVDB_USERNAME").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getName(),
                is(MY_APP_SERVDB_SECRET));
        assertThat(theVariableReferenceNamed("SERVDB_USERNAME").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is(KubeUtils.USERNAME_KEY));
        assertThat(theVariableReferenceNamed("SERVDB_PASSWORD").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed("SERVDB_URL").on(thePrimaryContainerOn(entandoDeployment)),
                is("jdbc:oracle:thin:@//mydb-service." + MY_APP_NAMESPACE + ".svc.cluster.local:1521/my_db"));
        assertThat(theVariableReferenceNamed("ORACLE_MAVEN_REPO").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is("oracleMavenRepo"));
        assertThat(theVariableReferenceNamed("ORACLE_REPO_USER").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is("oracleRepoUser"));
        assertThat(
                theVariableReferenceNamed("ORACLE_REPO_PASSWORD").on(thePrimaryContainerOn(entandoDeployment)).getSecretKeyRef().getKey(),
                is("oracleRepoPassword"));
        //But the db check on startup is disabled
        assertThat(theVariableNamed("DB_STARTUP_CHECK").on(thePrimaryContainerOn(entandoDeployment)), is("false"));

        //And another pod was created for PORTDB using the credentials and connection settings of the ExternalDatabase
        LabeledArgumentCaptor<Pod> portSchemaJobCaptor = forResourceWithLabel(Pod.class, ENTANDO_APP_LABEL_NAME, MY_APP)
                .andWithLabel(KubeUtils.DB_JOB_LABEL_NAME, MY_APP + "-db-preparation-job");
        verify(client.pods()).runToCompletion(portSchemaJobCaptor.capture());
        Pod entandoPortJob = portSchemaJobCaptor.getValue();
        verifyStandardSchemaCreationVariables("my-secret", MY_APP_SERVDB_SECRET,
                theInitContainerNamed(MY_APP + "-servdb-schema-creation-job").on(entandoPortJob), DbmsImageVendor.ORACLE);
        verifyStandardSchemaCreationVariables("my-secret", MY_APP_PORTDB_SECRET,
                theInitContainerNamed(MY_APP + "-portdb-schema-creation-job").on(entandoPortJob), DbmsImageVendor.ORACLE);
    }

    private EntandoDatabaseService buildExternalDatabase() {
        EntandoDatabaseService edb = new EntandoDatabaseService(
                new EntandoDatabaseServiceSpec(DbmsImageVendor.ORACLE, "myoracle.com", 1521, "my_db", "my-secret"));
        edb.getMetadata().setName("mydb");
        edb.getMetadata().setNamespace(MY_APP_NAMESPACE);
        return edb;
    }
}
