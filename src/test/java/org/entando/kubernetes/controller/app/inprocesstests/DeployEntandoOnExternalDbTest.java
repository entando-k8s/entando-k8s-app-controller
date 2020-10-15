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
import java.util.Collections;
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
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceSpec;
import org.entando.kubernetes.model.infrastructure.EntandoClusterInfrastructure;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("in-process"), @Tag("pre-deployment"), @Tag("component")})
class DeployEntandoOnExternalDbTest implements InProcessTestUtil, FluentTraversals {

    private static final String MY_APP_SERVDB_SECRET = MY_APP + "-servdb-secret";
    private static final String MY_APP_PORTDB_SECRET = MY_APP + "-portdb-secret";
    private final EntandoApp entandoApp = new EntandoAppBuilder(newTestEntandoApp()).editSpec().withDbms(DbmsVendor.ORACLE).endSpec()
            .build();
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
    void createCustomResources() {
        client.entandoResources().createOrPatchEntandoResource(externalDatabase);
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        entandoAppController = new EntandoAppController(client, keycloakClient);
        client.entandoResources().createOrPatchEntandoResource(entandoApp);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoApp.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoApp.getMetadata().getName());
    }

    @Test
    void testSecrets() {
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
    void testDeployment() {
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
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), entandoDeploymentCaptor.capture());
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
        //But the db check on startup is disabled
        assertThat(theVariableNamed("DB_STARTUP_CHECK").on(thePrimaryContainerOn(entandoDeployment)), is("false"));

        //And another pod was created for PORTDB using the credentials and connection settings of the ExternalDatabase
        LabeledArgumentCaptor<Pod> portSchemaJobCaptor = forResourceWithLabel(Pod.class, ENTANDO_APP_LABEL_NAME, MY_APP)
                .andWithLabel(KubeUtils.DB_JOB_LABEL_NAME, MY_APP + "-db-preparation-job");
        verify(client.pods()).runToCompletion(portSchemaJobCaptor.capture());
        Pod entandoPortJob = portSchemaJobCaptor.getValue();
        verifyStandardSchemaCreationVariables("my-secret", MY_APP_SERVDB_SECRET,
                theInitContainerNamed(MY_APP + "-servdb-schema-creation-job").on(entandoPortJob), DbmsVendor.ORACLE);
        verifyStandardSchemaCreationVariables("my-secret", MY_APP_PORTDB_SECRET,
                theInitContainerNamed(MY_APP + "-portdb-schema-creation-job").on(entandoPortJob), DbmsVendor.ORACLE);
    }

    private EntandoDatabaseService buildExternalDatabase() {
        EntandoDatabaseService edb = new EntandoDatabaseService(
                new EntandoDatabaseServiceSpec(DbmsVendor.ORACLE, "myoracle.com", 1521, "my_db", null, "my-secret",
                        Collections.emptyMap()));
        edb.getMetadata().setName("mydb");
        edb.getMetadata().setNamespace(MY_APP_NAMESPACE);
        return edb;
    }
}
