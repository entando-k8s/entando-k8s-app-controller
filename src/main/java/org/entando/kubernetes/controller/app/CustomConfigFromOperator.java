package org.entando.kubernetes.controller.app;

public class CustomConfigFromOperator {

    private String ecrPostInitConfiguration;
    private boolean tlsEnabled;

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public String getEcrPostInitConfiguration() {
        return ecrPostInitConfiguration;
    }

    public void setEcrPostInitConfiguration(String ecrPostInitConfiguration) {
        this.ecrPostInitConfiguration = ecrPostInitConfiguration;
    }

}
