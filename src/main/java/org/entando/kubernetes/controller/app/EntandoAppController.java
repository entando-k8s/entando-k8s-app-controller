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

import java.util.Collections;
import java.util.Optional;
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
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.deployable.PublicIngressingDeployable;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.AbstractServerStatus;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoAppController implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(EntandoAppController.class.getName());
    public static final String ENTANDO_K8S_SERVICE = "entando-k8s-service";
    private final KubernetesClientForControllers k8sClient;
    private final CapabilityProvider capabilityProvider;
    private final DeploymentProcessor deploymentProcessor;
    private final AtomicReference<EntandoApp> entandoApp = new AtomicReference<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    @Inject
    public EntandoAppController(KubernetesClientForControllers k8sClient, DeploymentProcessor deploymentProcessor,
            CapabilityProvider capabilityProvider) {
        this.k8sClient = k8sClient;
        this.capabilityProvider = capabilityProvider;
        this.deploymentProcessor = deploymentProcessor;
    }

    //There is no point re-interrupting the thread when the VM is about to exit.
    @SuppressWarnings("java:S2142")
    public void run() {
        this.entandoApp.set((EntandoApp) k8sClient.resolveCustomResourceToProcess(Collections.singletonList(EntandoApp.class)));
        try {
            final DatabaseConnectionInfo dbConnectionInfo = provideDatabaseIfRequired();
            final SsoConnectionInfo ssoConnectionInfo = provideSso();
            final long timeoutForDbAware = calculateDbAwareTimeout();
            queueDeployable(new EntandoAppServerDeployable(entandoApp.get(), ssoConnectionInfo, dbConnectionInfo), timeoutForDbAware);
            final long timeoutForNonDbAware = EntandoOperatorConfig.getPodReadinessTimeoutSeconds();
            queueDeployable(new AppBuilderDeployable(entandoApp.get(), ssoConnectionInfo), timeoutForNonDbAware);

            EntandoK8SService k8sService = new EntandoK8SService(k8sClient.loadControllerService(EntandoAppController.ENTANDO_K8S_SERVICE));
            queueDeployable(new ComponentManagerDeployable(entandoApp.get(), ssoConnectionInfo, k8sService, dbConnectionInfo),
                    timeoutForDbAware);
            executor.shutdown();
            final long totalTimeout = timeoutForDbAware * 2 + timeoutForNonDbAware;
            if (!executor.awaitTermination(totalTimeout, TimeUnit.SECONDS)) {
                throw new TimeoutException(format("Could not complete deployment of EntandoApp in %s seconds", totalTimeout));
            }
            if (!entandoApp.get().getStatus().hasFailed()) {
                entandoApp.updateAndGet(current -> k8sClient.updatePhase(current, EntandoDeploymentPhase.SUCCESSFUL));
            }
        } catch (Exception e) {
            attachControllerFailure(e, EntandoAppController.class, NameUtils.MAIN_QUALIFIER);
        }
        entandoApp.get().getStatus().findFailedServerStatus().ifPresent(s -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), s.getEntandoControllerFailure().getMessage());
        });
    }

    private long calculateDbAwareTimeout() {
        final long timeoutForDbAware;
        if (requiresDbmsService(EntandoAppHelper.determineDbmsVendor(entandoApp.get()))) {
            timeoutForDbAware =
                    EntandoOperatorConfig.getPodCompletionTimeoutSeconds() + EntandoOperatorConfig.getPodReadinessTimeoutSeconds();
        } else {
            timeoutForDbAware = EntandoOperatorConfig.getPodReadinessTimeoutSeconds();
        }
        return timeoutForDbAware;
    }

    private void queueDeployable(PublicIngressingDeployable<EntandoAppDeploymentResult> deployable, long timeout) {
        executor.submit(() -> {
            try {
                final EntandoAppDeploymentResult result = deploymentProcessor.processDeployable(deployable, (int) timeout);
                entandoApp.updateAndGet(current -> k8sClient.updateStatus(current, result.getStatus()));
            } catch (Exception e) {
                attachControllerFailure(e, deployable.getClass(), deployable.getQualifier().orElse(NameUtils.MAIN_QUALIFIER));
            }
        });
    }

    private void attachControllerFailure(Exception e, Class<?> theClass, String qualifier) {
        final Optional<AbstractServerStatus> serverStatus = entandoApp
                .updateAndGet(
                        current -> k8sClient.load(EntandoApp.class, current.getMetadata().getNamespace(), current.getMetadata().getName()))
                .getStatus()
                .getServerStatus(qualifier);
        if (serverStatus.map(AbstractServerStatus::hasFailed).orElse(false)) {
            //If the original failure has been attached to the entandoApp, leave it there and just log it
            serverStatus.ifPresent(st -> LOGGER.log(Level.SEVERE, st.getEntandoControllerFailure().getDetailMessage()));
        } else {
            //If the failure has not been attached to the entandoApp yet, do it now and log it.
            entandoApp.updateAndGet(current -> k8sClient.deploymentFailed(current, e, qualifier));
            LOGGER.log(Level.SEVERE, e, () -> format("Processing the class %s failed.", theClass.getSimpleName()));
        }
    }

    private ProvidedDatabaseCapability provideDatabaseIfRequired() throws TimeoutException {
        final DbmsVendor dbmsVendor = EntandoAppHelper.determineDbmsVendor(entandoApp.get());
        if (requiresDbmsService(dbmsVendor)) {
            return new ProvidedDatabaseCapability(capabilityProvider
                    .provideCapability(entandoApp.get(), new CapabilityRequirementBuilder()
                            .withCapability(StandardCapability.DBMS)
                            .withImplementation(StandardCapabilityImplementation.valueOf(dbmsVendor.name()))
                            .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.DEDICATED, CapabilityScope.CLUSTER)
                            .build(), 180));
        } else {
            return null;
        }
    }

    private boolean requiresDbmsService(DbmsVendor dbmsVendor) {
        return !Set.of(DbmsVendor.NONE, DbmsVendor.EMBEDDED).contains(dbmsVendor);
    }

    private ProvidedSsoCapability provideSso() throws TimeoutException {
        return new ProvidedSsoCapability(capabilityProvider
                .provideCapability(entandoApp.get(), new CapabilityRequirementBuilder()
                        .withCapability(StandardCapability.SSO)
                        .withPreferredDbms(determineDbmsForSso())
                        .withPreferredIngressHostName(entandoApp.get().getSpec().getIngressHostName().orElse(null))
                        .withPreferredTlsSecretName(entandoApp.get().getSpec().getTlsSecretName().orElse(null))
                        .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.CLUSTER)
                        .build(), 240));
    }

    private DbmsVendor determineDbmsForSso() {
        final DbmsVendor dbmsVendor = EntandoAppHelper.determineDbmsVendor(entandoApp.get());
        if (dbmsVendor == DbmsVendor.NONE) {
            return null;
        }
        return dbmsVendor;
    }

}
