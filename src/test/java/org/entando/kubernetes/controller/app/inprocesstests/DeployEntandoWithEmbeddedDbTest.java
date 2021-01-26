///*
// *
// * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
// *
// * This library is free software; you can redistribute it and/or modify it under
// * the terms of the GNU Lesser General License as published by the Free
// * Software Foundation; either version 2.1 of the License, or (at your option)
// * any later version.
// *
// *  This library is distributed in the hope that it will be useful, but WITHOUT
// * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// * FOR A PARTICULAR PURPOSE. See the GNU Lesser General License for more
// * details.
// *
// */

package org.entando.kubernetes.controller.app.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.container.SpringBootDeployableContainer.SpringProperty;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.junit.jupiter.api.AfterEach;
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
@SuppressWarnings("java:S6073")
class DeployEntandoWithEmbeddedDbTest implements InProcessTestUtil, FluentTraversals {

    private static final String MY_APP_SERVDB_SECRET = MY_APP + "-servdb-secret";
    private static final String MY_APP_PORTDB_SECRET = MY_APP + "-portdb-secret";
    private final EntandoApp entandoApp = new EntandoAppBuilder(newTestEntandoApp()).editSpec().withDbms(DbmsVendor.EMBEDDED).endSpec()
            .build();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    @InjectMocks
    private EntandoAppController entandoAppController;

    @BeforeEach
    void createCustomResources() {
        emulateKeycloakDeployment(client);
        emulateClusterInfrastuctureDeployment(client);
        entandoAppController = new EntandoAppController(client, keycloakClient);
        client.entandoResources().createOrPatchEntandoResource(entandoApp);
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty(), "true");
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoApp.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoApp.getMetadata().getName());
    }

    @AfterEach
    void removeJvmProps() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty());

    }

    @Test
    void testSecrets() {
        //Given I have created an EntandoApp with the spec.dbms property set to 'none'
        EntandoApp newEntandoApp = entandoApp;
        //When the EntanooAppController processes the addition request
        entandoAppController.onStartup(new StartupEvent());

        //Then no database secrets were created
        NamedArgumentCaptor<Secret> servSecretCaptor = forResourceNamed(Secret.class, MY_APP_SERVDB_SECRET);
        verify(client.secrets(), never()).createSecretIfAbsent(eq(entandoApp), servSecretCaptor.capture());
        NamedArgumentCaptor<Secret> portSecretCaptor = forResourceNamed(Secret.class, MY_APP_PORTDB_SECRET);
        verify(client.secrets(), never()).createSecretIfAbsent(eq(entandoApp), portSecretCaptor.capture());
    }

    @Test
    void testDeployment() {
        //Given I have created an EntandoApp with the spec.dbms property set to 'none'
        EntandoApp newEntandoApp = entandoApp;
        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());

        //Then a K8S deployment is created
        NamedArgumentCaptor<Deployment> appServerCaptor = forResourceNamed(Deployment.class,
                MY_APP + "-server-deployment");
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), appServerCaptor.capture());
        Deployment appServerDeployment = appServerCaptor.getValue();
        // And Entando has been configured to use the default embedded Derby database
        assertThat(theVariableNamed("PORTDB_DRIVER").on(theContainerNamed("server-container").on(appServerDeployment)),
                is("derby"));
        assertThat(theVariableNamed("SERVDB_DRIVER").on(theContainerNamed("server-container").on(appServerDeployment)),
                is("derby"));

        // And none of the variables overriding the default derby based environment variables have been overridden
        Container theServerContainer = theContainerNamed("server-container").on(appServerDeployment);
        assertTrue(theServerContainer.getEnv().stream().noneMatch(envVar -> isADatabaseVariableThatShouldBeOmitted(envVar)));

        //And the db check on startup is disabled
        assertThat(theVariableNamed("DB_STARTUP_CHECK").on(thePrimaryContainerOn(appServerDeployment)), is("false"));
        // And a volume mount has been set up reflecting the correct location of the derby database
        assertThat(theVolumeNamed(MY_APP + "-server-volume").on(appServerDeployment).getPersistentVolumeClaim().getClaimName(),
                is(MY_APP + "-server-pvc"));
        assertThat(theVolumeMountNamed(MY_APP + "-server-volume").on(thePrimaryContainerOn(appServerDeployment)).getMountPath(),
                is("/entando-data"));
        // And a PersistentVolumeClaim has been created for the derby database
        assertThat(this.client.persistentVolumeClaims().loadPersistentVolumeClaim(entandoApp, MY_APP + "-server-pvc"), not(nullValue()));

        // And the ComponentManager has been configured to use and embedded h2 database
        NamedArgumentCaptor<Deployment> componentManagerDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_APP + "-cm-deployment");
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), componentManagerDeploymentCaptor.capture());
        Deployment componentManagerDeployment = componentManagerDeploymentCaptor.getValue();
        assertThat(theVariableNamed(SpringProperty.SPRING_JPA_DATABASE_PLATFORM.name())
                        .on(theContainerNamed("de-container").on(componentManagerDeployment)),
                is(DbmsVendorConfig.H2.getHibernateDialect()));
        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_USERNAME.name())
                        .on(theContainerNamed("de-container").on(componentManagerDeployment)),
                is("sa"));
        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_PASSWORD.name())
                        .on(theContainerNamed("de-container").on(componentManagerDeployment)),
                is(""));
        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_URL.name())
                        .on(theContainerNamed("de-container").on(componentManagerDeployment)),
                is("jdbc:h2:file:/entando-data/databases/de/h2.db;DB_CLOSE_ON_EXIT=FALSE"));

        // And a volume mount has been set up reflecting the correct location of the h2 database
        assertThat(theVolumeNamed(MY_APP + "-de-volume").on(componentManagerDeployment).getPersistentVolumeClaim().getClaimName(),
                is(MY_APP + "-de-pvc"));
        assertThat(theVolumeMountNamed(MY_APP + "-de-volume").on(theContainerNamed("de-container").on(componentManagerDeployment))
                        .getMountPath(),
                is("/entando-data"));
        // And a PersistentVolumeClaim has been created for the derby database
        assertThat(this.client.persistentVolumeClaims().loadPersistentVolumeClaim(entandoApp, MY_APP + "-de-pvc"), not(nullValue()));
        assertThat(appServerDeployment.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup(), is(185L));
        verifyThatAllVolumesAreMapped(entandoApp, client, appServerDeployment);
    }

    private boolean isADatabaseVariableThatShouldBeOmitted(EnvVar envVar) {
        return !envVar.getName().endsWith("_DRIVER") && (envVar.getName().startsWith("PORTDB") || envVar.getName().startsWith("SERVDB"));
    }

}
