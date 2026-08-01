/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.a2ap.sample.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runs the Gateway sample against separately started protocol-1.0 Agents. */
@SpringBootApplication
public class GatewaySampleApplication {

    /** Starts the Gateway. */
    public static void main(String[] args) {
        SpringApplication.run(GatewaySampleApplication.class, args);
    }

}
