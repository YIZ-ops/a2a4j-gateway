/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.a2ap.gateway.core.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Redacts inbound credential headers before they reach logs, metrics or error details. */
public final class GatewaySecuritySanitizer {

    private GatewaySecuritySanitizer() {
    }

    /** Returns a redacted value for sensitive headers and the original value otherwise. */
    public static String redactHeader(String name, String value) {
        if (name == null) {
            return value;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization") || normalized.equals("cookie")
                || normalized.equals("set-cookie") || normalized.equals("x-a2a-api-key")
                ? "[REDACTED]" : value;
    }

    /** Returns a copy of headers with credential-bearing values redacted. */
    public static Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> result.put(name, redactHeader(name, value)));
        }
        return Map.copyOf(result);
    }

}
