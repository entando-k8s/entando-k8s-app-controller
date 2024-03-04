package org.entando.kubernetes.controller.app;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.List;

public class CustomConfigFromOperator {

    private String ecrPostInitConfiguration;
    private boolean tlsEnabled;

    private List<EnvVar> environmentVariablesAppEngine;
    private List<EnvVar> environmentVariablesComponentManager;
    private List<EnvVar> environmentVariablesSso;

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

    public List<EnvVar> getEnvironmentVariablesAppEngine() {
        return environmentVariablesAppEngine;
    }

    public void setEnvironmentVariablesAppEngine(List<EnvVar> environmentVariablesAppEngine) {
        this.environmentVariablesAppEngine = environmentVariablesAppEngine;
    }

    public List<EnvVar> getEnvironmentVariablesComponentManager() {
        return environmentVariablesComponentManager;
    }

    public void setEnvironmentVariablesComponentManager(List<EnvVar> environmentVariablesComponentManager) {
        this.environmentVariablesComponentManager = environmentVariablesComponentManager;
    }

    public List<EnvVar> getEnvironmentVariablesSso() {
        return environmentVariablesSso;
    }

    public void setEnvironmentVariablesSso(List<EnvVar> environmentVariablesSso) {
        this.environmentVariablesSso = environmentVariablesSso;
    }
}
