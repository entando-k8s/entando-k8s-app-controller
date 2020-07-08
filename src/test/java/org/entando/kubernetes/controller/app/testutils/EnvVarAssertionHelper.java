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

package org.entando.kubernetes.controller.app.testutils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import io.fabric8.kubernetes.api.model.Container;
import org.entando.kubernetes.controller.app.JbossDatasourceValidation;
import org.entando.kubernetes.controller.test.support.FluentTraversals;

public class EnvVarAssertionHelper {


    /**
     * does assertions on some environment variables.
     * @param dbPopulationJob the container on which do assertions
     * @param schemaPrefix the schema prefix to prepend to the env vars to check
     * @param fluentTraversals the class implementing FluentTraversals interface from which get actual schema prefix
     * @param dbService expected db service
     * @param appNamespace expected db app namespace
     * @param dbVendor expected db vendor name
     * @param dbName expected db name
     */
    public static void assertSchemaEnvironmentVariables(Container dbPopulationJob, String schemaPrefix, FluentTraversals fluentTraversals,
            String dbService, String appNamespace, String dbVendor, String port, String dbName) {

        assertThat(fluentTraversals.theVariableNamed(schemaPrefix + "URL").on(dbPopulationJob),
                is("jdbc:" + dbVendor + "://" + dbService + "." + appNamespace + ".svc.cluster.local:" + port + "/" + dbName));
        assertThat(fluentTraversals.theVariableNamed(schemaPrefix + "CONNECTION_CHECKER").on(dbPopulationJob),
                is("org.jboss.jca.adapters.jdbc.extensions.mysql.MySQLValidConnectionChecker"));
        assertThat(fluentTraversals.theVariableNamed(schemaPrefix + "EXCEPTION_SORTER").on(dbPopulationJob),
                is("org.jboss.jca.adapters.jdbc.extensions.mysql.MySQLExceptionSorter"));
    }
}
