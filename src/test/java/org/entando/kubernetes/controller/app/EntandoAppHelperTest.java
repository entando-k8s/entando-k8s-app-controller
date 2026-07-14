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

import static org.assertj.core.api.Assertions.assertThat;

import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class EntandoAppHelperTest {

    private static final String IMAGE = "entando/entando-de-app-tomcat";

    @Test
    void shouldUseTheDefault64MainlineWhenVersionIsMissing() {
        assertThat(EntandoAppHelper.appendImageVersion(appWithVersion(null), IMAGE))
                .isEqualTo(IMAGE + "-6-4");
    }

    @Test
    void shouldCollapseAny7xVersionToThe64ImageMainline() {
        for (String version : new String[]{"7.2", "7.3", "7.4", "7.5", "7.5.0", "7.5.1", "7.6.2"}) {
            assertThat(EntandoAppHelper.appendImageVersion(appWithVersion(version), IMAGE))
                    .as("version %s must resolve to the 6-4 image mainline", version)
                    .isEqualTo(IMAGE + "-6-4");
        }
    }

    @Test
    void shouldKeepNon7xCustomVersionsAsImageKeySuffix() {
        assertThat(EntandoAppHelper.appendImageVersion(appWithVersion("6.5"), IMAGE))
                .isEqualTo(IMAGE + "-6-5");
        assertThat(EntandoAppHelper.appendImageVersion(appWithVersion("6.4"), IMAGE))
                .isEqualTo(IMAGE + "-6-4");
    }

    private EntandoApp appWithVersion(String version) {
        return new EntandoAppBuilder()
                .withNewMetadata()
                .withName("my-app")
                .withNamespace("my-namespace")
                .endMetadata()
                .withNewSpec()
                .withEntandoAppVersion(version)
                .endSpec()
                .build();
    }
}