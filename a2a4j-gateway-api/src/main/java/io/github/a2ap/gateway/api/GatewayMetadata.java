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

package io.github.a2ap.gateway.api;

/** Stable metadata keys shared by routing, authorization, and observability. */
public final class GatewayMetadata {

    /** Request correlation id. */
    public static final String REQUEST_ID = "requestId";

    /** W3C trace id or equivalent tracing correlation id. */
    public static final String TRACE_ID = "traceId";

    /** Tenant identity established by the inbound authentication layer. */
    public static final String TENANT_ID = "tenantId";

    /** Gateway-owned task id exposed to callers. */
    public static final String GATEWAY_TASK_ID = "gatewayTaskId";

    /** Upstream agent task id, never used as the public task identity. */
    public static final String UPSTREAM_TASK_ID = "upstreamTaskId";

    /** Logical agent identity selected by the route resolver. */
    public static final String AGENT_ID = "agentId";

    /** Route decision identifier for audit and diagnostics. */
    public static final String ROUTE_DECISION_ID = "routeDecisionId";

    private GatewayMetadata() {
    }

}
