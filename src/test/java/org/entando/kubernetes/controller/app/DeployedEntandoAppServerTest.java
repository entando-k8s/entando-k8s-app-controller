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

import static io.qameta.allure.Allure.step;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.util.HashMap;
import java.util.Map;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.container.SpringBootDeployableContainer.SpringProperty;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.controller.support.common.EntandoImageResolver;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.test.common.SourceLink;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure"), @Tag("inner-hexagon")})
@Feature("As a deployer, I would like to deploy an EntandoApp directly so that I have more granular control over the "
        + "configuration settings")
@Issue("ENG-2284")
@SourceLink("DeployedEntandoAppServerTest.java")
@SuppressWarnings({"java:S5961"})//because this test is intended to generate documentation and should read like the generated document
class DeployedEntandoAppServerTest extends EntandoAppTestBase implements VariableReferenceAssertions {

    public static final String COMPONENT_MANAGER_IMAGE_OVERRIDE = "myregistry/myorg/johns-component-manager"
            + "@sha2561234561234";
    public static final String APP_BUILDER_IMAGE_OVERRIDE = "someregistry/someorg/apppbuild:54312";
    public static final String ENTANDO_EAP_IMAGE_OVERRIDE = "thepiratebay.com/pirates/of-the-carribean:1234";
    private EntandoApp app;

    @Test
    @Description("Should deploy the Entando EAP image with all the default values in a Red Hat compliant environment")
    void shouldDeployEntandoEapImageWithDefaultValues() {
        this.app = new EntandoAppBuilder()
                .withNewMetadata()
                .withName(MY_APP)
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();
        step("Given that the Entando Operator is running in 'Red Hat' compliance mode",
                () -> {
                    emulateEntandoK8SService();
                    attachEnvironmentVariable(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE,
                            EntandoOperatorComplianceMode.REDHAT.getName());
                });
        step("And the Operator runs in a Kubernetes environment the requires a filesystem user/group override for mounted volumes",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE, "true"));
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, "entando.org"));
        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        capabilityControllersForDbmsAndSsoAreRunning();
        step("When I create an EntandoApp with minimal configuration",
                () -> {
                    if (this.app.getMetadata().getResourceVersion() != null) {
                        this.app = getClient().entandoResources().reload(app);
                    }
                    runControllerAgainstCustomResource(app);
                });
        final EntandoApp entandoApp = client.entandoResources().load(EntandoApp.class, MY_NAMESPACE, MY_APP);
        step("Then a PostgreSQL DBMS Capability was provided :", () -> {
            final ProvidedCapability capability = getClient().entandoResources()
                    .load(ProvidedCapability.class, MY_NAMESPACE, "default-postgresql-dbms-in-namespace");
            assertThat(capability).isNotNull();
            assertThat(entandoApp.getStatus().getServerStatus(NameUtils.DB_QUALIFIER)).isPresent();
            attachKubernetesResource("PostgreSQL DBMS Capability", capability);
        });
        step("Then a Red Hat SSO Capability was provided", () -> {
            final ProvidedCapability capability = getClient().entandoResources()
                    .load(ProvidedCapability.class, MY_NAMESPACE, "default-sso-in-namespace");
            assertThat(capability).isNotNull();
            assertThat(capability.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).get().getExternalBaseUrl())
                    .contains("https://mykeycloak.apps.serv.run/auth");
            assertThat(entandoApp.getStatus().getServerStatus(NameUtils.SSO_QUALIFIER)).isPresent();
            attachKubernetesResource(" Red Hat SSO Capability", capability);
        });
        final String servDbSecret = "my-app-servdb-secret";
        final String portDbSecret = "my-app-portdb-secret";
        step("And a database schema was prepared for the Entando App and for Component Manager", () -> {
            final Pod mainDbPreprationJob = getClient().pods().loadPod(MY_NAMESPACE,
                    Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoApp", LabelNames.JOB_KIND.getName(), "db-preparation-job",
                            "EntandoApp",
                            MY_APP, LabelNames.DEPLOYMENT_QUALIFIER.getName(), NameUtils.MAIN_QUALIFIER));
            assertThat(mainDbPreprationJob).isNotNull();
            final Container portDbInitContainer = theInitContainerNamed("my-app-portdb-schema-creation-job").on(mainDbPreprationJob);
            verifyDbJobAdminVariables(entandoApp, portDbInitContainer);
            verifyDbJobSchemaCredentials(portDbSecret, portDbInitContainer);
            final Container servDbInitContainer = theInitContainerNamed("my-app-servdb-schema-creation-job").on(mainDbPreprationJob);
            verifyDbJobAdminVariables(entandoApp, servDbInitContainer);
            verifyDbJobSchemaCredentials(servDbSecret, servDbInitContainer);
            final Container populator = theInitContainerNamed("my-app-server-db-population-job").on(mainDbPreprationJob);
            verifyEntandoDbVariables(entandoApp, portDbSecret, "PORTDB", populator);
            verifyEntandoDbVariables(entandoApp, servDbSecret, "SERVDB", populator);
        });

        step("And a Kubernetes Deployment was created reflecting the requirements of the Entando Eap container:", () -> {
            final Deployment theEngineDeployment = client.deployments()
                    .loadDeployment(entandoApp, NameUtils.standardDeployment(entandoApp));
            attachKubernetesResource("Deployment", theEngineDeployment);
            final Container theEngineContainer = thePrimaryContainerOn(theEngineDeployment);
            step("using the Entando Eap Image",
                    () -> assertThat(theEngineContainer.getImage()).contains("entando/entando-de-app-eap"));
            step("With a volume mounted to the standard directory /entando-data",
                    () -> assertThat(theVolumeMountNamed("my-app-server-volume").on(theEngineContainer)
                            .getMountPath()).isEqualTo("/entando-data"));
            step("Which is bound to a PersistentVolumeClain", () -> {
                final PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                        .loadPersistentVolumeClaim(entandoApp, "my-app-server-pvc");
                attachKubernetesResource("PersistentVolumeClaim", pvc);
                assertThat(theVolumeNamed("my-app-server-volume").on(theEngineDeployment).getPersistentVolumeClaim()
                        .getClaimName()).isEqualTo(
                        "my-app-server-pvc");
            });
            step("And all the variables required to connect to Red Hat SSO have been configured", () -> {
                assertThat(theVariableNamed("KEYCLOAK_ENABLED").on(theEngineContainer)).isEqualTo("true");
                assertThat(theVariableNamed("KEYCLOAK_REALM").on(theEngineContainer)).isEqualTo("my-realm");
                assertThat(theVariableNamed("KEYCLOAK_PUBLIC_CLIENT_ID").on(theEngineContainer)).isEqualTo("entando-web");
                assertThat(theVariableNamed("KEYCLOAK_AUTH_URL").on(theEngineContainer)).isEqualTo("https://mykeycloak.apps.serv.run/auth");

                assertThat(theVariableReferenceNamed("KEYCLOAK_CLIENT_SECRET").on(theEngineContainer))
                        .matches(theSecretKey("my-app-secret", KeycloakName.CLIENT_SECRET_KEY));
                assertThat(theVariableReferenceNamed("KEYCLOAK_CLIENT_ID").on(theEngineContainer))
                        .matches(theSecretKey("my-app-secret", KeycloakName.CLIENT_ID_KEY));
            });
            step("And the File System User/Group override " + EntandoAppServerDeployable.DEFAULT_USERID_IN_JBOSS_BASE_IMAGES
                    + "has been applied to the mount", () ->
                    assertThat(theEngineDeployment.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup())
                            .isEqualTo(EntandoAppServerDeployable.DEFAULT_USERID_IN_JBOSS_BASE_IMAGES));
            verifyEntandoDbVariables(entandoApp, portDbSecret, "PORTDB", thePrimaryContainerOn(theEngineDeployment));
            verifyEntandoDbVariables(entandoApp, servDbSecret, "SERVDB", thePrimaryContainerOn(theEngineDeployment));

        });
        final String cmDbSecret = "my-app-dedb-secret";

        step("And a database schema was prepared for the Component Manager", () -> {
            final Pod cmDbPreprationJob = getClient().pods().loadPod(MY_NAMESPACE,
                    Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoApp", LabelNames.JOB_KIND.getName(), "db-preparation-job",
                            "EntandoApp",
                            MY_APP, LabelNames.DEPLOYMENT_QUALIFIER.getName(), "cm"));
            assertThat(cmDbPreprationJob).isNotNull();
            final Container cmDbInitContainer = theInitContainerNamed("my-app-dedb-schema-creation-job").on(cmDbPreprationJob);
            verifyDbJobAdminVariables(entandoApp, cmDbInitContainer);
            verifyDbJobSchemaCredentials(cmDbSecret, cmDbInitContainer);
        });
        step("And a Kubernetes Deployment was created reflecting the requirements of the Entando Component Manager image:", () -> {
            final Deployment componentManagerDeployment = client.deployments()
                    .loadDeployment(entandoApp, entandoApp.getMetadata().getName() + "-cm-" + NameUtils.DEFAULT_DEPLOYMENT_SUFFIX);
            attachKubernetesResource("Deployment", componentManagerDeployment);
            final Container theComponentManagerContainer = thePrimaryContainerOn(componentManagerDeployment);
            step("using the image 'entando/entando-component-manager'",
                    () -> assertThat(theComponentManagerContainer.getImage()).contains("entando/entando-component-manager"));
            step("With a volume mounted to the standard directory /entando-data",
                    () -> assertThat(theVolumeMountNamed("my-app-de-volume").on(theComponentManagerContainer)
                            .getMountPath()).isEqualTo("/entando-data"));
            step("Which is bound to a PersistentVolumeClaim", () -> {
                final PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                        .loadPersistentVolumeClaim(entandoApp, "my-app-de-pvc");
                attachKubernetesResource("PersistentVolumeClaim", pvc);
                assertThat(theVolumeNamed("my-app-de-volume").on(componentManagerDeployment).getPersistentVolumeClaim()
                        .getClaimName()).isEqualTo(
                        "my-app-de-pvc");
            });
            step("And all the variables required to connect to Red Hat SSO have been configured", () -> {
                verifySpringSecurityVariables(theComponentManagerContainer, "https://mykeycloak.apps.serv.run/auth/realms/my-realm",
                        "my-app-de-secret");
            });
            step("And a variables to connect to the entando-k8s-service", () -> {
                assertThat(theVariableNamed("ENTANDO_K8S_SERVICE_URL").on(theComponentManagerContainer))
                        .isEqualTo("http://entando-k8s-service.controller-namespace.svc.cluster.local:8084/k8s");
            });
            step("And a variables to connect to the app-engine instance", () -> {
                assertThat(theVariableNamed("ENTANDO_URL").on(theComponentManagerContainer))
                        .isEqualTo("http://my-app-service.my-namespace.svc.cluster.local:8080/entando-de-app");
            });
            step("And the File System User/Group override " + ComponentManagerDeployable.COMPONENT_MANAGER_CURRENT_USER
                    + "has been applied to the mount", () ->
                    assertThat(componentManagerDeployment.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup())
                            .isEqualTo(EntandoAppServerDeployable.DEFAULT_USERID_IN_JBOSS_BASE_IMAGES));
            step("and the credentials for the  schema user provided in a newly generated secret for the Component Manager deployment: "
                            + "'" + cmDbSecret + "'",
                    () -> {
                        attachKubernetesResource("Schema User Secret", getClient().secrets().loadSecret(entandoApp, cmDbSecret));
                        assertThat(theVariableReferenceNamed(SpringProperty.SPRING_DATASOURCE_PASSWORD.name())
                                .on(theComponentManagerContainer))
                                .matches(theSecretKey(cmDbSecret, SecretUtils.PASSSWORD_KEY));
                        assertThat(theVariableReferenceNamed(SpringProperty.SPRING_DATASOURCE_USERNAME.name())
                                .on(theComponentManagerContainer))
                                .matches(theSecretKey(cmDbSecret, SecretUtils.USERNAME_KEY));
                        assertThat(theVariableNamed(SpringProperty.SPRING_DATASOURCE_URL.name()).on(theComponentManagerContainer))
                                .isEqualTo(
                                        "jdbc:postgresql://default-postgresql-dbms-in-namespace-service.my-namespace.svc.cluster"
                                                + ".local:5432/my_db");
                    });

        });

        step("And a Kubernetes Deployment was created reflecting the requirements of the AppBuilder image:", () -> {
            final Deployment appBuilderDeployment = client.deployments()
                    .loadDeployment(entandoApp, entandoApp.getMetadata().getName() + "-ab-" + NameUtils.DEFAULT_DEPLOYMENT_SUFFIX);
            attachKubernetesResource("Deployment", appBuilderDeployment);
            final Container theAppBuilder = thePrimaryContainerOn(appBuilderDeployment);
            step("using the image 'entando/app-builder'",
                    () -> assertThat(theAppBuilder.getImage()).contains("entando/app-builder"));
            step("And all the variable required to connect to the Entando Engine has been configured", () -> {
                assertThat(theVariableNamed("DOMAIN")
                        .on(theAppBuilder)).isEqualTo("/entando-de-app");

            });
            step("And a healthcheck path has been configured that will not trigger an authentication flow", () -> {
                assertThat(theAppBuilder.getStartupProbe().getHttpGet().getPath()).isEqualTo("/app-builder/favicon-entando.png");
                assertThat(theAppBuilder.getReadinessProbe().getHttpGet().getPath()).isEqualTo("/app-builder/favicon-entando.png");
                assertThat(theAppBuilder.getLivenessProbe().getHttpGet().getPath()).isEqualTo("/app-builder/favicon-entando.png");
            });
        });

        step("And a Kubernetes Service was created for the Entando Engine deployment", () -> {
            final Service service = client.services()
                    .loadService(entandoApp, NameUtils.standardServiceName(entandoApp));
            attachKubernetesResource("Service", service);
            step("Targeting port 8080 in the Deployment",
                    () -> assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(8080));
            step("And with a label selector matching the labels of the Pod Template on the  Deployment",
                    () -> assertThat(service.getSpec().getSelector()).containsAllEntriesOf(
                            Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoApp", "EntandoApp",
                                    entandoApp.getMetadata().getName(),
                                    LabelNames.DEPLOYMENT.getName(), entandoApp.getMetadata().getName())
                    ));
        });
        step("And a Kubernetes Service was created for the Component Manager deployment", () -> {
            final Service service = client.services()
                    .loadService(entandoApp, entandoApp.getMetadata().getName() + "-cm-" + NameUtils.DEFAULT_SERVICE_SUFFIX);
            attachKubernetesResource("Service", service);
            step("Targeting port 8083 in the Deployment",
                    () -> assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(8083));
            step("And with a label selector matching the labels of the Pod Template on the  Deployment",
                    () -> assertThat(service.getSpec().getSelector()).containsAllEntriesOf(
                            Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoApp", "EntandoApp",
                                    entandoApp.getMetadata().getName(),
                                    LabelNames.DEPLOYMENT.getName(), entandoApp.getMetadata().getName() + "-cm")
                    ));
        });
        step("And a Kubernetes Service was created for the AppBuilder deployment", () -> {
            final Service service = client.services()
                    .loadService(entandoApp, entandoApp.getMetadata().getName() + "-ab-" + NameUtils.DEFAULT_SERVICE_SUFFIX);
            attachKubernetesResource("Service", service);
            step("Targeting port 8081 in the Deployment",
                    () -> assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(8081));
            step("And with a label selector matching the labels of the Pod Template on the  Deployment",
                    () -> assertThat(service.getSpec().getSelector()).containsAllEntriesOf(
                            Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoApp", "EntandoApp",
                                    entandoApp.getMetadata().getName(),
                                    LabelNames.DEPLOYMENT.getName(), entandoApp.getMetadata().getName() + "-ab")
                    ));
        });

        step("And a Kubernetes Ingress was created:", () -> {
            final Ingress ingress = client.ingresses()
                    .loadIngress(entandoApp.getMetadata().getNamespace(), NameUtils.standardIngressName(entandoApp));
            attachKubernetesResource("Ingress", ingress);
            step("With a hostname derived from the EntandoApp name, namespace and the routing suffix", () ->
                    assertThat(ingress.getSpec().getRules().get(0).getHost())
                            .isEqualTo(MY_APP + "-" + MY_NAMESPACE + "." + ROUTING_SUFFIX));
            step("And the path '/entando-de-app' is mapped to the servce 'my-app-service'", () ->
                    assertThat(theHttpPath("/entando-de-app").on(ingress).getBackend().getServiceName()).isEqualTo("my-app-service"));
            step("And the path '/app-builder/' is mapped to the servce 'my-app-ab-service'", () ->
                    assertThat(theHttpPath("/app-builder/").on(ingress).getBackend().getServiceName()).isEqualTo("my-app-ab-service"));
            step("And the path '/digital-exchange' is mapped to the service 'my-app-de-service'", () ->
                    assertThat(theHttpPath("/digital-exchange").on(ingress).getBackend().getServiceName())
                            .isEqualTo("my-app-cm-service"));
            step("And with TLS configured to use the default TLS secret", () -> {
                assertThat(ingress.getSpec().getTls().get(0).getHosts())
                        .contains(MY_APP + "-" + MY_NAMESPACE + "." + ROUTING_SUFFIX);
                assertThat(ingress.getSpec().getTls().get(0).getSecretName()).isEqualTo(DEFAULT_TLS_SECRET);
            });
        });

        step("And the default TLS secret was cloned into the EntandoApp's deployment namespace", () -> {
            final Secret secret = client.secrets().loadSecret(entandoApp, DEFAULT_TLS_SECRET);
            attachKubernetesResource("Default TLS Secret", secret);
            assertThat(secret.getType()).isEqualTo("kubernetes.io/tls");

        });
        attachKubernetesState();
    }

    private void emulateEntandoK8SService() {
        getClient().services()
                .createOrReplaceService(new TestResource().withNames(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, "ignored"),
                        new ServiceBuilder()
                                .withNewMetadata()
                                .withName(EntandoAppController.ENTANDO_K8S_SERVICE)
                                .withNamespace(AbstractK8SClientDouble.CONTROLLER_NAMESPACE)
                                .endMetadata()
                                .withNewSpec()
                                .addNewPort()
                                .withPort(8084)
                                .endPort()
                                .endSpec()
                                .build());
    }

    @Test
    @Description("Should deploy the images specified in the annotations for a given version of Entando")
    void shouldDeployImagesInAnnotations() {
        this.app = new EntandoAppBuilder()
                .withNewMetadata()
                .withName(MY_APP)
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withEntandoAppVersion("6.5")
                .endSpec()
                .build();
        step("Given that the Entando Operator is running in 'Red Hat' compliance mode",
                () -> {
                    emulateEntandoK8SService();
                    attachEnvironmentVariable(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE,
                            EntandoOperatorComplianceMode.REDHAT.getName());
                });
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, "entando.org"));
        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        capabilityControllersForDbmsAndSsoAreRunning();
        step("When I create an EntandoApp with image override annotations",
                () -> {
                    HashMap<String, String> annotations = new HashMap<>();
                    annotations.put(EntandoImageResolver.IMAGE_OVERRIDE_ANNOTATION_PREFIX + "entando-component-manager-6-5",
                            COMPONENT_MANAGER_IMAGE_OVERRIDE);
                    annotations.put(EntandoImageResolver.IMAGE_OVERRIDE_ANNOTATION_PREFIX + "app-builder-6-5",
                            APP_BUILDER_IMAGE_OVERRIDE);
                    annotations.put(EntandoImageResolver.IMAGE_OVERRIDE_ANNOTATION_PREFIX + "entando-de-app-eap-6-5",
                            ENTANDO_EAP_IMAGE_OVERRIDE);
                    this.app.getMetadata().setAnnotations(annotations);
                    if (this.app.getMetadata().getResourceVersion() != null) {
                        this.app = getClient().entandoResources().reload(app);
                    }
                    runControllerAgainstCustomResource(app);
                });
        final EntandoApp entandoApp = client.entandoResources().load(EntandoApp.class, MY_NAMESPACE, MY_APP);
        step("And a Kubernetes Deployment was created reflecting the correct Entando Eap image:", () -> {
            final Deployment theEngineDeployment = client.deployments()
                    .loadDeployment(entandoApp, NameUtils.standardDeployment(entandoApp));
            attachKubernetesResource("Deployment", theEngineDeployment);
            final Container theEngineContainer = thePrimaryContainerOn(theEngineDeployment);
            step(format("using the Entando Eap Image '%s'", ENTANDO_EAP_IMAGE_OVERRIDE),
                    () -> assertThat(theEngineContainer.getImage()).isEqualTo(ENTANDO_EAP_IMAGE_OVERRIDE));
        });
        step("And a Kubernetes Deployment was created reflecting the requirements of the Entando Component Manager image:", () -> {
            final Deployment componentManagerDeployment = client.deployments()
                    .loadDeployment(entandoApp, entandoApp.getMetadata().getName() + "-cm-" + NameUtils.DEFAULT_DEPLOYMENT_SUFFIX);
            attachKubernetesResource("Deployment", componentManagerDeployment);
            final Container theComponentManagerContainer = thePrimaryContainerOn(componentManagerDeployment);
            step(format("using the image '%s'", COMPONENT_MANAGER_IMAGE_OVERRIDE),
                    () -> assertThat(theComponentManagerContainer.getImage()).isEqualTo(COMPONENT_MANAGER_IMAGE_OVERRIDE));
        });

        step("And a Kubernetes Deployment was created reflecting the requirements of the AppBuilder image:", () -> {
            final Deployment appBuilderDeployment = client.deployments()
                    .loadDeployment(entandoApp, entandoApp.getMetadata().getName() + "-ab-" + NameUtils.DEFAULT_DEPLOYMENT_SUFFIX);
            attachKubernetesResource("Deployment", appBuilderDeployment);
            final Container theAppBuilder = thePrimaryContainerOn(appBuilderDeployment);
            step(format("using the image '%s'", APP_BUILDER_IMAGE_OVERRIDE),
                    () -> assertThat(theAppBuilder.getImage()).isEqualTo(APP_BUILDER_IMAGE_OVERRIDE));
        });

        attachKubernetesState();
    }

    @Test
    @Description("Should point ComponentManager to the globally configured Component Repository Namespaces")
    void shouldPointComponentManagerToGlobalRepositoryNamespaces() {
        this.app = new EntandoAppBuilder()
                .withNewMetadata()
                .withName(MY_APP)
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();
        step("Given that I have configured the Component Repository namespaces 'ecr1' and 'ecr2'",
                () -> {
                    emulateEntandoK8SService();
                    attachEnvironmentVariable(EntandoAppConfigProperty.ENTANDO_COMPONENT_REPOSITORY_NAMESPACES, "ecr1,ecr2");

                });
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, "entando.org"));
        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        capabilityControllersForDbmsAndSsoAreRunning();
        step("When I create an EntandoApp ",
                () -> {
                    if (this.app.getMetadata().getResourceVersion() != null) {
                        this.app = getClient().entandoResources().reload(app);
                    }
                    runControllerAgainstCustomResource(app);
                });
        final EntandoApp entandoApp = client.entandoResources().load(EntandoApp.class, MY_NAMESPACE, MY_APP);
        final Deployment componentManagerDeployment = client.deployments()
                .loadDeployment(entandoApp, entandoApp.getMetadata().getName() + "-cm-" + NameUtils.DEFAULT_DEPLOYMENT_SUFFIX);
        step("Then a Kubernetes Deployment was created for the Entando Component Manager image:", () -> {
            attachKubernetesResource("Deployment", componentManagerDeployment);
        });
        step("And the Component Manager container points to the previously configured ComponentRepository namespaces", () -> {
            assertThat(theVariableNamed("ENTANDO_COMPONENT_REPOSITORY_NAMESPACES").on(
                    thePrimaryContainerOn(componentManagerDeployment))).isEqualTo("ecr1,ecr2");
        });
        attachKubernetesState();
    }

    private void capabilityControllersForDbmsAndSsoAreRunning() {
        step("And there is a controller to process requests for the DBMS capability",
                () -> doAnswer(withADatabaseCapabilityStatus(DbmsVendor.POSTGRESQL, "my_db")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));
        step("And there is a controller to process requests for the SSO capability",
                () -> doAnswer(withAnSsoCapabilityStatus("mykeycloak.apps.serv.run", "my-realm")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.SSO)), anyInt()));
    }

    private void verifyEntandoDbVariables(EntandoApp entandoApp, String dbSecret, String variablePrefix, Container theEngineContainer) {
        step("and the credentials for the  schema user provided in a newly generated secret for the Entando EAP deployment: "
                        + "'" + dbSecret + "'",
                () -> {
                    attachKubernetesResource("Schema User Secret", getClient().secrets().loadSecret(entandoApp, dbSecret));
                    assertThat(theVariableReferenceNamed(variablePrefix + "_PASSWORD").on(theEngineContainer))
                            .matches(theSecretKey(dbSecret, SecretUtils.PASSSWORD_KEY));
                    assertThat(theVariableReferenceNamed(variablePrefix + "_USERNAME").on(theEngineContainer))
                            .matches(theSecretKey(dbSecret, SecretUtils.USERNAME_KEY));
                    assertThat(theVariableNamed(variablePrefix + "_URL").on(theEngineContainer))
                            .isEqualTo(
                                    "jdbc:postgresql://default-postgresql-dbms-in-namespace-service.my-namespace.svc.cluster"
                                            + ".local:5432/my_db");
                    assertThat(theVariableNamed(variablePrefix + "_EXCEPTION_SORTER").on(theEngineContainer))
                            .isEqualTo("org.jboss.jca.adapters.jdbc.extensions.postgres.PostgreSQLExceptionSorter");
                    assertThat(theVariableNamed(variablePrefix + "_CONNECTION_CHECKER").on(theEngineContainer))
                            .isEqualTo("org.jboss.jca.adapters.jdbc.extensions.postgres.PostgreSQLValidConnectionChecker");

                });
    }

    private void verifyDbJobAdminVariables(EntandoApp entandoApp, Container initContainer) {
        attachKubernetesResource("PostgreSQL Admin Secret",
                getClient().secrets().loadSecret(entandoApp, "default-postgresql-dbms-in-namespace-admin-secret"));
        step("using a cluster local connection to the database Service", () -> {
            assertThat(theVariableNamed("DATABASE_SERVER_HOST").on(initContainer))
                    .isEqualTo("default-postgresql-dbms-in-namespace-service.my-namespace.svc.cluster.local");
            assertThat(theVariableNamed("DATABASE_SERVER_PORT").on(initContainer))
                    .isEqualTo(String.valueOf(DbmsVendorConfig.POSTGRESQL.getDefaultPort()));
        });
        verifyDbJobAdminCredentials("default-postgresql-dbms-in-namespace-admin-secret", initContainer);
    }

}
