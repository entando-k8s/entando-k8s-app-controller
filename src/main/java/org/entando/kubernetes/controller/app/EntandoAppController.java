package org.entando.kubernetes.controller.app;

import static java.util.Optional.of;
import static org.entando.kubernetes.controller.KubeUtils.ENTANDO_KEYCLOAK_REALM;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.DeployCommand;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.common.InfrastructureConfig;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.app.EntandoApp;

public class EntandoAppController extends AbstractDbAwareController<EntandoApp> {

    @Inject
    public EntandoAppController(KubernetesClient kubernetesClient) {
        super(kubernetesClient);
    }

    public EntandoAppController(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient) {
        super(k8sClient, keycloakClient);
    }

    public EntandoAppController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoApp entandoApp) {
        EntandoAppServerDeployable deployable = buildEntandoAppServerDeployable(entandoApp);
        performDeployCommand(deployable);
        grantOperatorSuperuserRoleOnEntando(entandoApp);
    }

    private DeployCommand<ServiceDeploymentResult> performDeployCommand(EntandoAppServerDeployable deployable) {
        DeployCommand<ServiceDeploymentResult> deployCommand = new DeployCommand<>(deployable);
        deployCommand.execute(k8sClient, of(keycloakClient));
        k8sClient.entandoResources().updateStatus(deployable.getCustomResource(), deployCommand.getStatus());
        return deployCommand;
    }

    private EntandoAppServerDeployable buildEntandoAppServerDeployable(EntandoApp entandoApp) {
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources().findKeycloak(entandoApp);
        InfrastructureConfig infrastructureConfig = findInfrastructureConfig(entandoApp);
        DatabaseServiceResult databaseServiceResult = prepareDatabaseService(entandoApp, entandoApp.getSpec().getDbms().orElse(
                DbmsVendor.POSTGRESQL), "db");
        return new EntandoAppServerDeployable(
                entandoApp,
                keycloakConnectionConfig,
                infrastructureConfig,
                databaseServiceResult);
    }

    private InfrastructureConfig findInfrastructureConfig(EntandoApp entandoApp) {
        InfrastructureConfig infrastructureConfig = k8sClient.entandoResources().findInfrastructureConfig(entandoApp);
        if (infrastructureConfig.getInfrastructureSecret() == null) {
            return null;
        }
        return infrastructureConfig;
    }

    private void grantOperatorSuperuserRoleOnEntando(EntandoApp entandoApp) {
        //TODO this is ugly but will fall away once the App/Plugin decoupling happens
        String entandoAppClientId = EntandoAppDeployableContainer.clientIdOf(entandoApp);
        KeycloakClientConfig config = new KeycloakClientConfig(ENTANDO_KEYCLOAK_REALM, "entando-k8s-operator", null);
        config.withPermission(entandoAppClientId, "superuser");
        keycloakClient.updateClient(config);
    }

}
