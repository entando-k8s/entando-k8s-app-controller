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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.entando.kubernetes.controller.common.KeycloakConnectionSecret;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.model.app.EntandoApp;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class EntandoAppDeployableContainerTest implements InProcessTestUtil {

    private EntandoApp entandoApp = newTestEntandoApp();
    @Mock
    private KeycloakConnectionSecret keycloakConnectionSecret;

    private EntandoAppDeployableContainer entandoAppDeployableContainer =
            new EntandoAppDeployableContainer(entandoApp, keycloakConnectionSecret);

    @Test
    void getHealthCheckPathTest() {

        //noinspection OptionalGetWithoutIsPresent
        assertEquals(EntandoAppDeployableContainer.INGRESS_WEB_CONTEXT + EntandoAppDeployableContainer.HEALTH_CHECK_PATH,
                entandoAppDeployableContainer.getHealthCheckPath().get());
    }
}
