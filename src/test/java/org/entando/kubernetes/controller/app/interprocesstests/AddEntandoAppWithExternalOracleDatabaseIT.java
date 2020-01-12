package org.entando.kubernetes.controller.app.interprocesstests;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.entando.kubernetes.controller.integrationtest.support.EntandoAppIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("oracle-end-to-end")
public class AddEntandoAppWithExternalOracleDatabaseIT extends AddEntandoAppBaseIT {

    @Test
    public void create() {
        //Given I have an external Oracle database
        helper.externalDatabases()
                .prepareExternalOracleDatabase(EntandoAppIntegrationTestHelper.TEST_NAMESPACE, "TEST_ENTANDO_CONFIGDB",
                        "TEST_ENTANDO_PORTDB",
                        "TEST_ENTANDO_SERVDB");
        EntandoApp entandoApp = new EntandoAppBuilder().withNewSpec()
                .withStandardServerImage(JeeServer.WILDFLY)
                .withDbms(DbmsImageVendor.ORACLE)
                .withIngressHostName(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "." + helper.getDomainSuffix())
                .withReplicas(1)
                .withEntandoImageVersion("6.0.0")
                .withTlsSecretName(null)
                .endSpec()
                .build();

        entandoApp.setMetadata(new ObjectMeta());
        entandoApp.getMetadata().setNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE);
        SampleWriter.writeSample(entandoApp, "app-with-external-oracle-db");

        entandoApp.getMetadata().setName(EntandoAppIntegrationTestHelper.TEST_APP_NAME);
        createAndWaitForApp(entandoApp, 0, false);
        verifyAllExpectedResources();
    }

    @Override
    protected void verifyEntandoDbDeployment() {
        //Nothing to do here
    }
}

