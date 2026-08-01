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

package io.github.a2ap.gateway.core.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/** Validates Agent Card URLs and blocks common SSRF destinations by default. */
public final class AgentCardUrlPolicy {

    private final boolean allowHttp;

    private final boolean allowPrivateNetwork;

    private final Set<String> allowedCidrs;

    private final int maxResponseBytes;

    /**
     * Creates a URL policy.
     *
     * @param allowHttp whether plain HTTP is allowed for development
     * @param allowPrivateNetwork whether private and link-local addresses are allowed
     * @param allowedCidrs explicit private-network CIDR allow-list
     * @param maxResponseBytes maximum accepted card response size
     */
    public AgentCardUrlPolicy(boolean allowHttp, boolean allowPrivateNetwork,
            Set<String> allowedCidrs, int maxResponseBytes) {
        this.allowHttp = allowHttp;
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.allowedCidrs = allowedCidrs == null ? Set.of() : Set.copyOf(allowedCidrs);
        if (maxResponseBytes < 1024) {
            throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /** Returns a conservative production-oriented policy. */
    public static AgentCardUrlPolicy productionDefault() {
        return new AgentCardUrlPolicy(false, false, Set.of(), 1024 * 1024);
    }

    /** Validates syntax and scheme without performing DNS resolution. */
    public URI validateConfiguredUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cardUrl must not be blank");
        }
        final URI uri;
        try {
            uri = new URI(value.trim());
        }
        catch (URISyntaxException ex) {
            throw new IllegalArgumentException("cardUrl is not a valid URI", ex);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https")
                || (allowHttp && scheme.equalsIgnoreCase("http")))) {
            throw new IllegalArgumentException("cardUrl must use HTTPS by default");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("cardUrl must contain a host and no userinfo or fragment");
        }
        if (!allowPrivateNetwork && isObviouslyPrivateHost(uri.getHost())) {
            throw new IllegalArgumentException("cardUrl points to a blocked private host");
        }
        return uri;
    }

    /** Resolves and validates every address to mitigate DNS rebinding and SSRF. */
    public void validateResolved(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isBlocked(address) && !isAllowed(address)) {
                    throw new IllegalArgumentException("cardUrl resolves to a blocked address");
                }
            }
        }
        catch (java.net.UnknownHostException ex) {
            throw new IllegalArgumentException("cardUrl host cannot be resolved", ex);
        }
    }

    /** Returns the response size limit in bytes. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    private boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || "169.254.169.254".equals(address.getHostAddress());
    }

    private boolean isAllowed(InetAddress address) {
        if (allowPrivateNetwork) {
            return true;
        }
        for (String cidr : allowedCidrs) {
            if (matchesCidr(address, cidr)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidr(InetAddress address, String cidr) {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2 || !(address instanceof Inet4Address)) {
            return false;
        }
        try {
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (!(network instanceof Inet4Address) || prefix < 0 || prefix > 32) {
                return false;
            }
            byte[] actual = address.getAddress();
            byte[] expected = network.getAddress();
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            int actualValue = toInt(actual);
            int expectedValue = toInt(expected);
            return (actualValue & mask) == (expectedValue & mask);
        }
        catch (RuntimeException | java.net.UnknownHostException ex) {
            return false;
        }
    }

    private static int toInt(byte[] value) {
        return (value[0] & 0xff) << 24 | (value[1] & 0xff) << 16
                | (value[2] & 0xff) << 8 | value[3] & 0xff;
    }

    private static boolean isObviouslyPrivateHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.equals("metadata.google.internal") || normalized.equals("169.254.169.254");
    }

}
