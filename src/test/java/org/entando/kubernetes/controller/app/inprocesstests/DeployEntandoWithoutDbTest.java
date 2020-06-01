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
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.app.EntandoAppController;
import org.entando.kubernetes.controller.database.DbmsVendorStrategy;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.spi.SpringBootDeployableContainer.SpringProperty;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
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
public class DeployEntandoWithoutDbTest implements InProcessTestUtil, FluentTraversals {

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
    public void createCustomResources() {
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        entandoAppController = new EntandoAppController(client, keycloakClient);
        client.entandoResources().createOrPatchEntandoResource(entandoApp);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoApp.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoApp.getMetadata().getName());
    }

    @Test
    public void testSecrets() {
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
    public void testDeployment() {
        //Given I have created an EntandoApp with the spec.dbms property set to 'none'
        EntandoApp newEntandoApp = entandoApp;
        //When the DeployCommand processes the addition request
        entandoAppController.onStartup(new StartupEvent());

        //Then a K8S deployment is created
        NamedArgumentCaptor<Deployment> entandoDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_APP + "-server-deployment");
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoApp), entandoDeploymentCaptor.capture());
        Deployment entandoDeployment = entandoDeploymentCaptor.getValue();
        // And Entando has been configured to use the default embedded Derby database
        assertThat(theVariableNamed("PORTDB_DRIVER").on(theContainerNamed("server-container").on(entandoDeployment)),
                is(DbmsVendorStrategy.DERBY.getHibernateDialect()));
        assertThat(theVariableNamed("SERVDB_DRIVER").on(theContainerNamed("server-container").on(entandoDeployment)),
                is(DbmsVendorStrategy.DERBY.getHibernateDialect()));
        //But the db check on startup is disabled
        assertThat(theVariableNamed("DB_STARTUP_CHECK").on(thePrimaryContainerOn(entandoDeployment)), is("false"));

        // And the ComponentManager has been configured to use and embedded h2 database
        assertThat(theVariableNamed(SpringProperty.SPRING_JPA_DATABASE_PLATFORM.name())
                        .on(theContainerNamed("de-container").on(entandoDeployment)),
                is(DbmsVendorStrategy.H2.getHibernateDialect()));
        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_USERNAME.name())
                        .on(theContainerNamed("de-container").on(entandoDeployment)),
                is("sa"));
        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_PASSWORD.name())
                        .on(theContainerNamed("de-container").on(entandoDeployment)),
                is(""));
        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_URL.name())
                        .on(theContainerNamed("de-container").on(entandoDeployment)),
                is("jdbc:h2:file:/entando-data/de/h2.db;DB_CLOSE_ON_EXIT=FALSE"));
        // And a volume mount has been set up reflecting the correct location of the h2 database
        assertThat(theVolumeNamed(MY_APP + "-server-volume").on(entandoDeployment).getPersistentVolumeClaim().getClaimName(), is(MY_APP + "-server-pvc"));
        assertThat(theVolumeMountNamed(MY_APP + "-server-volume").on(thePrimaryContainerOn(entandoDeployment)).getMountPath(),
                is("/entando-data"));
        // And a PersistentVolumeClaim has been created for the h2 database
        assertThat(this.client.persistentVolumeClaims().loadPersistentVolumeClaim(entandoApp, MY_APP + "-server-pvc"), not(nullValue()));
        verifyThatAllVolumesAreMapped(entandoApp, client, entandoDeployment);
    }

}
