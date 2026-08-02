/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.a2ap.core.util;

import java.util.Map;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Message;

/** Single SDK-backed codec boundary for A2A standard domain values. */
public final class SdkModelCodec {

    private SdkModelCodec() {
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return JsonUtil.fromJson(json, type);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid A2A SDK " + type.getSimpleName(), ex);
        }
    }

    public static String toJson(Object value) {
        try {
            return JsonUtil.toJson(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not encode A2A SDK model", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object value) {
        return fromJson(toJson(value), Map.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> messageMap(Message message) {
        Map<String, Object> encoded = toMap(message);
        Object wrapped = encoded.get("message");
        return wrapped instanceof Map<?, ?> ? (Map<String, Object>) wrapped : encoded;
    }

}
