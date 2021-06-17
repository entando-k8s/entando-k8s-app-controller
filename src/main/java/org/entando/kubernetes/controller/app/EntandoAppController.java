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
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.deployable.PublicIngressingDeployable;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.ServerStatus;
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
            entandoApp.set(k8sClient.deploymentStarted(entandoApp.get()));
            final DatabaseConnectionInfo dbConnectionInfo = provideDatabaseIfRequired();
            final SsoConnectionInfo ssoConnectionInfo = provideSso();
            final int timeoutForDbAware = calculateDbAwareTimeout();
            queueDeployable(new EntandoAppServerDeployable(entandoApp.get(), ssoConnectionInfo, dbConnectionInfo), timeoutForDbAware);
            final int timeoutForNonDbAware = EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
            queueDeployable(new AppBuilderDeployable(entandoApp.get(), ssoConnectionInfo), timeoutForNonDbAware);
            EntandoK8SService k8sService = new EntandoK8SService(k8sClient.loadControllerService(EntandoAppController.ENTANDO_K8S_SERVICE));
            queueDeployable(new ComponentManagerDeployable(entandoApp.get(), ssoConnectionInfo, k8sService, dbConnectionInfo),
                    timeoutForDbAware);
            executor.shutdown();
            final int totalTimeout = timeoutForDbAware * 2 + timeoutForNonDbAware;
            if (!executor.awaitTermination(totalTimeout, TimeUnit.SECONDS)) {
                throw new TimeoutException(format("Could not complete deployment of EntandoApp in %s seconds", totalTimeout));
            }
            entandoApp.updateAndGet(k8sClient::deploymentEnded);
        } catch (Exception e) {
            attachControllerFailure(e, EntandoAppController.class, NameUtils.MAIN_QUALIFIER);
        }
        entandoApp.get().getStatus().findFailedServerStatus().flatMap(ServerStatus::getEntandoControllerFailure).ifPresent(s -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), s.getMessage());
        });
    }

    private int calculateDbAwareTimeout() {
        //TODO Meh.... who cares.
        final int timeoutForDbAware;
        if (requiresDbmsService(EntandoAppHelper.determineDbmsVendor(entandoApp.get()))) {
            timeoutForDbAware =
                    EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds() + EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        } else {
            timeoutForDbAware = EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        }
        return timeoutForDbAware;
    }

    private void queueDeployable(PublicIngressingDeployable<EntandoAppDeploymentResult> deployable, long timeout) {
        executor.submit(() -> {
            try {
                deploymentProcessor.processDeployable(deployable, (int) timeout);
            } catch (Exception e) {
                attachControllerFailure(e, deployable.getClass(), deployable.getQualifier().orElse(NameUtils.MAIN_QUALIFIER));
            }
        });
    }

    private void attachControllerFailure(Exception e, Class<?> theClass, String qualifier) {
        entandoApp.updateAndGet(current -> k8sClient.deploymentFailed(current, e, qualifier));
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
                            .build(), 180);
            capabilityResult.getProvidedCapability().getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                    this.entandoApp.updateAndGet(a -> this.k8sClient.updateStatus(a, new ServerStatus(NameUtils.DB_QUALIFIER, s))));
            capabilityResult.getControllerFailure().ifPresent(f -> {
                throw new EntandoControllerException(format("Could not prepare database for EntandoApp %s/%s%n%s", entandoApp.get()
                                .getMetadata().getNamespace(), entandoApp.get()
                                .getMetadata().getName(),
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
                        .build(), 240);
        capabilityResult.getProvidedCapability().getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                this.entandoApp.updateAndGet(a -> this.k8sClient.updateStatus(a, new ServerStatus(NameUtils.SSO_QUALIFIER, s))));
        capabilityResult.getControllerFailure().ifPresent(f -> {
            throw new EntandoControllerException(format("Could not prepare SSO for EntandoApp %s/%s%n%s", entandoApp.get()
                            .getMetadata().getNamespace(), entandoApp.get()
                            .getMetadata().getName(),
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

}
