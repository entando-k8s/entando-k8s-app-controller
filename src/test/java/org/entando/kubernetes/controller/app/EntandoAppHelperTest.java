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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EntandoAppHelperTest {

    @Test
    void testCombineEnvironmentVariables() {
        // Both empty
        List<EnvVar> a = new ArrayList<>();
        List<EnvVar> b = new ArrayList<>();
        List<EnvVar> result = EntandoAppHelper.combineEnvironmentVariables(a, b);
        assertTrue(result.isEmpty());

        //  "a" is empty, "b" has one element
        a = new ArrayList<>();
        b = new ArrayList<>();
        b.add(new EnvVar("VAR1", "value1", null));
        result = EntandoAppHelper.combineEnvironmentVariables(a, b);
        assertEquals(1, result.size());
        assertEquals("VAR1", result.get(0).getName());
        assertEquals("value1", result.get(0).getValue());

        //  "a" is null, "b" has one element
        a = null;
        b = new ArrayList<>();
        b.add(new EnvVar("VAR1", "value1", null));
        result = EntandoAppHelper.combineEnvironmentVariables(a, b);
        assertEquals(1, result.size());
        assertEquals("VAR1", result.get(0).getName());
        assertEquals("value1", result.get(0).getValue());

        // "a" has one element, "b" is null
        a = new ArrayList<>();
        a.add(new EnvVar("VAR2", "value2", null));
        b = null;
        result = EntandoAppHelper.combineEnvironmentVariables(a, b);
        assertEquals(1, result.size());
        assertEquals("VAR2", result.get(0).getName());
        assertEquals("value2", result.get(0).getValue());

        //  Both lists have different elements
        a = new ArrayList<>();
        a.add(new EnvVar("VAR3", "value3", null));
        b = new ArrayList<>();
        b.add(new EnvVar("VAR4", "value4", null));
        result = EntandoAppHelper.combineEnvironmentVariables(a, b);
        assertEquals(2, result.size());
        assertTrue(result.contains(new EnvVar("VAR3", "value3", null)));
        assertTrue(result.contains(new EnvVar("VAR4", "value4", null)));

        // Both lists have the same elements
        a = new ArrayList<>();
        a.add(new EnvVar("VAR5", "value5", null));
        b = new ArrayList<>();
        b.add(new EnvVar("VAR5", "value5", null));
        result = EntandoAppHelper.combineEnvironmentVariables(a, b);
        assertEquals(1, result.size());
        assertEquals("VAR5", result.get(0).getName());
        assertEquals("value5", result.get(0).getValue());
    }

    @Test
    void testSubtractEnvironmentVariables() {
        // With no variable in common
        List<EnvVar> a = new ArrayList<>();
        a.add(new EnvVar("A", "ValueA", null));
        a.add(new EnvVar("B", "ValueB", null));

        List<EnvVar> b = new ArrayList<>();
        b.add(new EnvVar("C", "ValueC", null));
        b.add(new EnvVar("D", "ValueD", null));

        List<EnvVar> result = EntandoAppHelper.subtractEnvironmentVariables(a, b);

        assertEquals(a.size(), result.size());
        assertEquals(a, result);

        // with variables in common
        a = new ArrayList<>();
        a.add(new EnvVar("A", "ValueA", null));
        a.add(new EnvVar("B", "ValueB", null));

        b = new ArrayList<>();
        b.add(new EnvVar("A", "ValueA", null));
        b.add(new EnvVar("C", "ValueC", null));

        List<EnvVar> expected = new ArrayList<>();
        expected.add(new EnvVar("B", "ValueB", null));

        result = EntandoAppHelper.subtractEnvironmentVariables(a, b);

        assertEquals(expected.size(), result.size());
        assertEquals(expected, result);

        // "b" is empty
        a = new ArrayList<>();
        a.add(new EnvVar("A", "ValueA", null));
        a.add(new EnvVar("B", "ValueB", null));

        result = EntandoAppHelper.subtractEnvironmentVariables(a, new ArrayList<>());

        assertEquals(a.size(), result.size());
        assertEquals(a, result);

        // "a" is null and "b" is not empty
        a = null;
        b = new ArrayList<>();
        b.add(new EnvVar("B", "ValueB", null));

        result = EntandoAppHelper.subtractEnvironmentVariables(a, b);

        assertEquals(0, result.size());
    }
}