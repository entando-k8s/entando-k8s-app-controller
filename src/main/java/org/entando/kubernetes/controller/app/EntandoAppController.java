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

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.deployable.IngressingDeployable;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.ServerStatus;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoAppController implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(EntandoAppController.class.getName());
    public static final String ENTANDO_K8S_SERVICE = "entando-k8s-service";
    private final KubernetesClientForControllers k8sClientForControllers;
    private final KubernetesClient k8sClient;
    private final CapabilityProvider capabilityProvider;
    private final DeploymentProcessor deploymentProcessor;
    private final AtomicReference<EntandoApp> entandoApp = new AtomicReference<>();
    private final ExecutorService executor = mkExecutorService();

    private ExecutorService mkExecutorService() {
        String dpenv = System.getenv("ENTANDO_DEPLOYMENT_PARALLELISM");
        int dp = (dpenv != null) ? Integer.parseInt(dpenv) : 0;
        if (dp > 0 && dp <= 3) {
            LOGGER.log(Level.INFO, () -> format("Deployment parallelism explicitly set to: %d", dp));
            return Executors.newFixedThreadPool(dp);
        } else {
            return Executors.newFixedThreadPool(3);
        }
    }

    @Inject
    public EntandoAppController(KubernetesClientForControllers k8sClientForControllers, DeploymentProcessor deploymentProcessor,
            CapabilityProvider capabilityProvider, KubernetesClient k8sClient) {
        this.k8sClientForControllers = k8sClientForControllers;
        this.capabilityProvider = capabilityProvider;
        this.deploymentProcessor = deploymentProcessor;
        this.k8sClient = k8sClient;
    }

    //There is no point re-interrupting the thread when the VM is about to exit.
    @SuppressWarnings("java:S2142")
    @Override
    public void run() {
        this.entandoApp.set(
                (EntandoApp) k8sClientForControllers.resolveCustomResourceToProcess(Collections.singletonList(EntandoApp.class)));
        try {
            entandoApp.set(k8sClientForControllers.deploymentStarted(entandoApp.get()));
            this.createDefaultLimitRange();
            final DatabaseConnectionInfo dbConnectionInfo = provideDatabaseIfRequired();
            final SsoConnectionInfo ssoConnectionInfo = provideSso();
            final int timeoutForDbAware = calculateDbAwareTimeout();
            queueDeployable(new EntandoAppServerDeployable(entandoApp.get(), ssoConnectionInfo, dbConnectionInfo), timeoutForDbAware);
            final int timeoutForNonDbAware = EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
            queueDeployable(new AppBuilderDeployable(entandoApp.get()), timeoutForNonDbAware);
            EntandoK8SService k8sService = new EntandoK8SService(
                    k8sClientForControllers.loadControllerService(EntandoAppController.ENTANDO_K8S_SERVICE));
            queueDeployable(new ComponentManagerDeployable(entandoApp.get(), ssoConnectionInfo, k8sService, dbConnectionInfo),
                    timeoutForDbAware);
            executor.shutdown();
            final int totalTimeout = timeoutForDbAware * 2 + timeoutForNonDbAware;
            if (!executor.awaitTermination(totalTimeout, TimeUnit.SECONDS)) {
                throw new TimeoutException(format("Could not complete deployment of EntandoApp in %s seconds", totalTimeout));
            }
            entandoApp.updateAndGet(k8sClientForControllers::deploymentEnded);
        } catch (Exception e) {
            attachControllerFailure(e, EntandoAppController.class, NameUtils.MAIN_QUALIFIER);
        }
        entandoApp.get().getStatus().findFailedServerStatus().flatMap(ServerStatus::getEntandoControllerFailure).ifPresent(s -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), s.getDetailMessage());
        });
    }

    private int calculateDbAwareTimeout() {
        final int timeoutForDbAware;
        if (requiresDbmsService(EntandoAppHelper.determineDbmsVendor(entandoApp.get()))) {
            timeoutForDbAware =
                    EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds() + EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        } else {
            timeoutForDbAware = EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        }
        return timeoutForDbAware;
    }

    private void queueDeployable(IngressingDeployable<EntandoAppDeploymentResult> deployable, long timeout) {
        executor.submit(() -> {
            try {
                EntandoAppDeploymentResult result = deploymentProcessor.processDeployable(deployable, (int) timeout);
                entandoApp.getAndUpdate(ea -> k8sClientForControllers.updateStatus(ea, result.getStatus()));
            } catch (Exception e) {
                attachControllerFailure(e, deployable.getClass(), deployable.getQualifier().orElse(NameUtils.MAIN_QUALIFIER));
            }
        });
    }

    private void attachControllerFailure(Exception e, Class<?> theClass, String qualifier) {
        entandoApp.updateAndGet(current -> k8sClientForControllers.deploymentFailed(current, e, qualifier));
        LOGGER.log(Level.SEVERE, e, () -> format("Processing the class %s failed.: %n%s", theClass.getSimpleName(),
                entandoApp.get().getStatus().getServerStatus(qualifier).flatMap(ServerStatus::getEntandoControllerFailure)
                        .orElseThrow(IllegalStateException::new).getDetailMessage()));
    }

    private ProvidedDatabaseCapability provideDatabaseIfRequired() throws TimeoutException {
        final DbmsVendor dbmsVendor = EntandoAppHelper.determineDbmsVendor(entandoApp.get());
        if (requiresDbmsService(dbmsVendor)) {
            final CapabilityProvisioningResult capabilityResult = capabilityProvider
                    .provideCapability(entandoApp.get(), new CapabilityRequirementBuilder()
                            .withCapability(StandardCapability.DBMS)
                            .withImplementation(StandardCapabilityImplementation.valueOf(dbmsVendor.name()))
                            .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.DEDICATED, CapabilityScope.CLUSTER)
                            .build(), EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds());
            final ProvidedCapability dbmsCapability = capabilityResult.getProvidedCapability();
            dbmsCapability.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                    this.entandoApp.updateAndGet(
                            a -> this.k8sClientForControllers.updateStatus(a, new ServerStatus(NameUtils.DB_QUALIFIER, s))));
            capabilityResult.getControllerFailure().ifPresent(f -> {
                throw new EntandoControllerException(dbmsCapability,
                        format("Could not prepare DBMS  capability for EntandoApp %s/%s. Please inspect the ProvidedCapability %s/%s, "
                                        + "address the "
                                        + "deployment failure and force a redeployment using the annotation value 'entando"
                                        + ".org/processing-instruction: force. The following message was received:%n %s",
                                entandoApp.get().getMetadata().getNamespace(),
                                entandoApp.get().getMetadata().getName(),
                                dbmsCapability.getMetadata().getNamespace(),
                                dbmsCapability.getMetadata().getName(),
                                f.getDetailMessage()));
            });
            return new ProvidedDatabaseCapability(capabilityResult);
        } else {
            return null;
        }
    }

    private boolean requiresDbmsService(DbmsVendor dbmsVendor) {
        return !Set.of(DbmsVendor.NONE, DbmsVendor.EMBEDDED).contains(dbmsVendor);
    }

    private ProvidedSsoCapability provideSso() throws TimeoutException {
        final CapabilityProvisioningResult capabilityResult = capabilityProvider
                .provideCapability(entandoApp.get(), new CapabilityRequirementBuilder()
                        .withCapability(StandardCapability.SSO)
                        .withPreferredDbms(determineDbmsForSso())
                        .withPreferredIngressHostName(entandoApp.get().getSpec().getIngressHostName().orElse(null))
                        .withPreferredTlsSecretName(entandoApp.get().getSpec().getTlsSecretName().orElse(null))
                        .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.CLUSTER)
                        .build(), EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds() + EntandoOperatorSpiConfig
                        .getPodReadinessTimeoutSeconds());
        final ProvidedCapability ssoCapability = capabilityResult.getProvidedCapability();
        ssoCapability.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                this.entandoApp.updateAndGet(
                        a -> this.k8sClientForControllers.updateStatus(a, new ServerStatus(NameUtils.SSO_QUALIFIER, s))));
        capabilityResult.getControllerFailure().ifPresent(f -> {
            throw new EntandoControllerException(ssoCapability,
                    format("Could not prepare SSO capability for EntandoApp %s/%s. Please inspect the ProvidedCapability %s/%s, address "
                                    + "the "
                                    + "deployment failure and force a redeployment using the annotation value 'entando"
                                    + ".org/processing-instruction: force. The following message was received:%n %s",
                            entandoApp.get().getMetadata().getNamespace(),
                            entandoApp.get().getMetadata().getName(),
                            ssoCapability.getMetadata().getNamespace(),
                            ssoCapability.getMetadata().getName(),
                            f.getDetailMessage()));
        });
        return new ProvidedSsoCapability(capabilityResult);
    }

    private DbmsVendor determineDbmsForSso() {
        final DbmsVendor dbmsVendor = EntandoAppHelper.determineDbmsVendor(entandoApp.get());
        if (dbmsVendor == DbmsVendor.NONE) {
            return null;
        }
        return dbmsVendor;
    }

    /**
     * create the default LimitRange.
     */
    public void createDefaultLimitRange() {

        String namespace = this.k8sClient.getNamespace();

        final LimitRange limitRange = new LimitRangeBuilder()
                .withNewMetadata()
                .withName("storagelimits")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .addNewLimit()
                .withType("PersistentVolumeClaim")
                .withMax(Map.of("storage", Quantity.parse("1000Gi")))
                .withMin(Map.of("storage", Quantity.parse("100Mi")))
                .endLimit()
                .endSpec()
                .build();

        this.k8sClient.limitRanges()
                .inNamespace(namespace)
                .create(limitRange);
    }
}
