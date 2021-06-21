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

package org.entando.kubernetes.controller.app;

import static io.qameta.allure.Allure.attachment;
import static io.qameta.allure.Allure.step;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.SupportProducer;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.JeeServer;
import org.entando.kubernetes.test.common.KeycloakTestCapabilityProvider;
import org.entando.kubernetes.test.e2etest.ControllerExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("smoke"), @Tag("allure"), @Tag("post-deployment"), @Tag("inter-process")})
@Feature("As an Entando Operator users, I want to use a Docker container to process an EntandoApp so that I don't need to "
        + "know any of its implementation details to use it.")
class EntandoAppSmokeTest implements FluentIntegrationTesting {

    private static final String MY_NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("my-namespace");
    public static final String MY_APP = EntandoOperatorTestConfig.calculateName("my-app");
    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    private EntandoApp entandoApp;

    @Test
    @Description("Should successfully connect to newly deployed Keycloak Server")
    void testDeployment() {
        KubernetesClient client = new SupportProducer().getKubernetesClient();
        final DefaultSimpleK8SClient simpleClient = new DefaultSimpleK8SClient(client);
        step("Given I have a clean namespace", () -> {
            TestFixturePreparation.prepareTestFixture(client, deleteAll(EntandoApp.class).fromNamespace(MY_NAMESPACE));
        });
        step("And I have configured a ProvidedCapability for SSO in the namespace", () -> {
            final ProvidedCapability providedCapability = new KeycloakTestCapabilityProvider(simpleClient, MY_NAMESPACE)
                    .provideKeycloakCapability();
            attachment("SSO Capability", objectMapper.writeValueAsString(providedCapability));
        });
        step("And I have created an EntandoApp custom resource", () -> {
            this.entandoApp = simpleClient.entandoResources()
                    .createOrPatchEntandoResource(
                            new EntandoAppBuilder()
                                    .withNewMetadata()
                                    .withNamespace(MY_NAMESPACE)
                                    .withName(MY_APP)
                                    .endMetadata()
                                    .withNewSpec()
                                    .withStandardServerImage(JeeServer.EAP)
                                    .withDbms(DbmsVendor.EMBEDDED)
                                    .endSpec()
                                    .build()
                    );
            attachment("SSO Capability", objectMapper.writeValueAsString(this.entandoApp));
        });
        step("When I run the entando-k8s-app-controller container against the EntandoApp", () -> {
            ControllerExecutor executor = new ControllerExecutor(MY_NAMESPACE, simpleClient,
                    r -> "entando-k8s-app-controller");
            executor.runControllerFor(Action.ADDED, entandoApp,
                    EntandoOperatorTestConfig.getVersionOfImageUnderTest().orElse("0.0.0-11"));
        });
        step("Then I can successfully login into the newly deployed Entando server", () -> {
            final String strUrl =
                    HttpTestHelper.getDefaultProtocol() + "://" + MY_APP + "-" + MY_NAMESPACE + "." + EntandoOperatorConfig
                            .getDefaultRoutingSuffix().orElse("apps.serv.run")
                            + "/entando-de-app/api/health";
            await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().until(() -> HttpTestHelper.statusOk(strUrl));
            assertThat(HttpTestHelper.statusOk(strUrl)).isTrue();
        });
        //TODO spin up the AppBuilder test container here.
    }

}
