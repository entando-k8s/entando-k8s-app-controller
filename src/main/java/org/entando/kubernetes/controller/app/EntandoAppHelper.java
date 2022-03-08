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

import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.KeycloakToUse;

public class EntandoAppHelper {

    public static final String FIRST_DECOUPLED_ENTANDO_VERSION = "6.4";

    private EntandoAppHelper() {

    }

    public static DbmsVendor determineDbmsVendor(EntandoApp entandoApp) {
        if (EntandoOperatorSpiConfig.getComplianceMode() == EntandoOperatorComplianceMode.REDHAT) {
            return entandoApp.getSpec().getDbms().orElse(DbmsVendor.POSTGRESQL);
        } else {
            return entandoApp.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED);
        }
    }

    public static String determineRealm(EntandoApp entandoApp, SsoConnectionInfo ssoConnectionInfo) {
        return entandoApp.getSpec().getKeycloakToUse().flatMap(KeycloakToUse::getRealm).or(ssoConnectionInfo::getDefaultRealm)
                .orElse(KeycloakName.ENTANDO_DEFAULT_KEYCLOAK_REALM);
    }

    public static String appendImageVersion(EntandoApp entandoApp, String imageName) {
        String entandoAppVersion = entandoApp.getSpec().getEntandoAppVersion().orElse(FIRST_DECOUPLED_ENTANDO_VERSION);
        // 7.0 version will become 6.4 internally, so it is not required to change all the references to 6-4 when resolving the images
        if (entandoAppVersion.equals("7.0")) {
            entandoAppVersion = "6.4";
        }
        return imageName + "-" + entandoAppVersion.replace('.', '-');
    }
}
