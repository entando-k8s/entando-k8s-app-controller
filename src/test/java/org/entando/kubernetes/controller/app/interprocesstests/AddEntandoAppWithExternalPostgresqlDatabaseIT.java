package org.entando.kubernetes.controller.app.interprocesstests;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.entando.kubernetes.controller.integrationtest.support.EntandoAppIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process")})
public class AddEntandoAppWithExternalPostgresqlDatabaseIT extends AddEntandoAppBaseIT {

    @Test
    public void create() {
        helper.externalDatabases().prepareExternalPostgresqlDatabase(EntandoAppIntegrationTestHelper.TEST_NAMESPACE, "EntandoApp");
        EntandoApp entandoApp = new EntandoAppBuilder().withNewSpec()
                .withStandardServerImage(JeeServer.WILDFLY)
                .withDbms(DbmsVendor.POSTGRESQL)
                .withIngressHostName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "." + helper.getDomainSuffix())
                .withReplicas(1)
                .withEntandoImageVersion("6.0.0")
                .withTlsSecretName(null)
                .endSpec()
                .build();
        entandoApp.setMetadata(new ObjectMeta());
        entandoApp.getMetadata().setNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE);
        SampleWriter.writeSample(entandoApp, "app-with-external-postgresql-db");
        entandoApp.getMetadata().setName(EntandoAppIntegrationTestHelper.TEST_APP_NAME);
        createAndWaitForApp(entandoApp, 0, false);
        verifyAllExpectedResources();
    }

    @Override
    protected void verifyEntandoDbDeployment() {
        //Nothing to do here
    }
}

