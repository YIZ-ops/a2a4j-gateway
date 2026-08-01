/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.api;

import java.util.Optional;
import java.util.regex.Pattern;

/** Small W3C Trace Context parser used without requiring an OpenTelemetry SDK. */
public record GatewayTraceContext(String traceparent, String traceId, String spanId, String traceFlags) {

    private static final Pattern TRACEPARENT = Pattern.compile(
            "^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");

    public GatewayTraceContext {
        requireText(traceparent, "traceparent");
        requireText(traceId, "traceId");
        requireText(spanId, "spanId");
        requireText(traceFlags, "traceFlags");
    }

    /** Parses a version 00 W3C traceparent, rejecting all-zero identifiers. */
    public static Optional<GatewayTraceContext> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var matcher = TRACEPARENT.matcher(value.trim());
        if (!matcher.matches() || allZero(matcher.group(1)) || allZero(matcher.group(2))) {
            return Optional.empty();
        }
        return Optional.of(new GatewayTraceContext(value.trim(), matcher.group(1).toLowerCase(),
                matcher.group(2).toLowerCase(), matcher.group(3).toLowerCase()));
    }

    /** Returns the trace id from a valid parent, or a stable fallback. */
    public static String traceIdOr(String value, String fallback) {
        return parse(value).map(GatewayTraceContext::traceId).orElse(fallback);
    }

    private static boolean allZero(String value) {
        return value.chars().allMatch(ch -> ch == '0');
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
