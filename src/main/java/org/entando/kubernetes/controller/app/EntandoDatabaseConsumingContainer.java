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

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.database.DatabaseSchemaCreationResult;
import org.entando.kubernetes.controller.database.DbmsVendorStrategy;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.IngressingContainer;

public abstract class EntandoDatabaseConsumingContainer implements DbAware, IngressingContainer {

    private static final String PORTDB = "portdb";
    private static final String SERVDB = "servdb";
    private static final String PORTDB_PREFIX = "PORTDB_";
    private static final String SERVDB_PREFIX = "SERVDB_";
    private Map<String, DatabaseSchemaCreationResult> dbSchemas = new ConcurrentHashMap<>();

    protected DatabasePopulator buildDatabasePopulator() {
        return new EntandoAppDatabasePopulator(this);
    }

    @Override
    public String getWebContextPath() {
        return "/entando-de-app";
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath());
    }

    @Override
    public void addEnvironmentVariables(List<EnvVar> vars) {
        vars.add(new EnvVar("DB_STARTUP_CHECK", "false", null));
        addEntandoDbConnectionVars(vars, this.dbSchemas.get(PORTDB), PORTDB_PREFIX);
        addEntandoDbConnectionVars(vars, this.dbSchemas.get(SERVDB), SERVDB_PREFIX);
    }

    private void addEntandoDbConnectionVars(List<EnvVar> vars, DatabaseSchemaCreationResult dbDeploymentResult, String varNamePrefix) {
        final DbmsVendorStrategy vendor;
        final String jdbcUrl;

        if (dbDeploymentResult == null) {
            vendor = DbmsVendorStrategy.DERBY;
            jdbcUrl = DbmsVendorStrategy.DERBY.getConnectionStringBuilder().usingDatabase("production").buildConnectionString();
        } else {
            vendor = dbDeploymentResult.getVendor();
            jdbcUrl = dbDeploymentResult.getJdbcUrl();
        }

        vars.add(new EnvVar(varNamePrefix + "DRIVER", vendor.getHibernateDialect(), null));
        vars.add(new EnvVar(varNamePrefix + "URL", jdbcUrl, null));

        Optional.ofNullable(dbDeploymentResult).ifPresent(result -> {
            vars.add(new EnvVar(varNamePrefix + "USERNAME", null,
                    KubeUtils.secretKeyRef(result.getSchemaSecretName(), KubeUtils.USERNAME_KEY)));
            vars.add(new EnvVar(varNamePrefix + "PASSWORD", null,
                    KubeUtils.secretKeyRef(result.getSchemaSecretName(), KubeUtils.PASSSWORD_KEY)));

        });
    }

    @Override
    public List<String> getDbSchemaQualifiers() {
        return Arrays.asList(PORTDB, SERVDB);
    }

    @Override
    public Optional<DatabasePopulator> useDatabaseSchemas(Map<String, DatabaseSchemaCreationResult> dbSchemas) {
        this.dbSchemas = Optional.ofNullable(dbSchemas).orElse(new ConcurrentHashMap<>());
        return Optional.of(buildDatabasePopulator());
    }

    public static class EntandoAppDatabasePopulator implements DatabasePopulator {

        private final EntandoDatabaseConsumingContainer entandoAppDeployableContainer;

        public EntandoAppDatabasePopulator(EntandoDatabaseConsumingContainer entandoAppDeployableContainer) {
            this.entandoAppDeployableContainer = entandoAppDeployableContainer;
        }

        @Override
        public String determineImageToUse() {
            return entandoAppDeployableContainer.determineImageToUse();
        }

        @Override
        public String[] getCommand() {
            return new String[]{"/bin/bash", "-c", "/entando-common/init-db-from-deployment.sh"};
        }

        @Override
        public void addEnvironmentVariables(List<EnvVar> vars) {
            entandoAppDeployableContainer.addEnvironmentVariables(vars);
        }

    }

}
