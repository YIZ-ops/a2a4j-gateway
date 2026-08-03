/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.gateway.core.exception;

/** Raised when the negotiated A2A protocol version is not supported by the gateway. */
public final class VersionNotSupportedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Creates a version negotiation failure. */
    public VersionNotSupportedException(String version) {
        super("unsupported A2A-Version: " + version);
    }

}
