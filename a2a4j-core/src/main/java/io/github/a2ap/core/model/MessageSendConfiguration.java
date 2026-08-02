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

package io.github.a2ap.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for sending messages in the A2A4J framework.
 */
public class MessageSendConfiguration {

    /**
     * accepted output modalities by the client
     */
    private List<String> acceptedOutputModes;

    /**
     * number of recent messages to be retrieved
     */
    private Integer historyLength;

    /**
     * where the server should send notifications when disconnected.
     */
    private PushNotificationConfig pushNotificationConfig;

    /** If true, return the created task without waiting for completion. */
    @JsonProperty("returnImmediately")
    private Boolean returnImmediately;

    /**
     * Default constructor for serialization frameworks.
     */
    public MessageSendConfiguration() {
    }

    public MessageSendConfiguration(List<String> acceptedOutputModes, Integer historyLength,
                                    PushNotificationConfig pushNotificationConfig, Boolean blocking) {
        this.acceptedOutputModes = acceptedOutputModes;
        this.historyLength = historyLength;
        this.pushNotificationConfig = pushNotificationConfig;
        this.returnImmediately = blocking == null ? null : !blocking;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> getAcceptedOutputModes() {
        return acceptedOutputModes;
    }

    public void setAcceptedOutputModes(List<String> acceptedOutputModes) {
        this.acceptedOutputModes = acceptedOutputModes;
    }

    public Integer getHistoryLength() {
        return historyLength;
    }

    public void setHistoryLength(Integer historyLength) {
        this.historyLength = historyLength;
    }

    public PushNotificationConfig getPushNotificationConfig() {
        return pushNotificationConfig;
    }

    public void setPushNotificationConfig(PushNotificationConfig pushNotificationConfig) {
        this.pushNotificationConfig = pushNotificationConfig;
    }

    public Boolean getReturnImmediately() {
        return returnImmediately;
    }

    public void setReturnImmediately(Boolean returnImmediately) {
        this.returnImmediately = returnImmediately;
    }

    /** Compatibility accessor for the pre-1.0 inverse blocking flag. */
    @Deprecated
    @JsonIgnore
    public Boolean getBlocking() {
        return returnImmediately == null ? null : !returnImmediately;
    }

    /** Compatibility mutator for the pre-1.0 inverse blocking flag. */
    @Deprecated
    @JsonIgnore
    public void setBlocking(Boolean blocking) {
        this.returnImmediately = blocking == null ? null : !blocking;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        MessageSendConfiguration that = (MessageSendConfiguration) o;
        return Objects.equals(acceptedOutputModes, that.acceptedOutputModes)
                && Objects.equals(historyLength, that.historyLength)
                && Objects.equals(pushNotificationConfig, that.pushNotificationConfig)
                && Objects.equals(returnImmediately, that.returnImmediately);
    }

    @Override
    public int hashCode() {
        return Objects.hash(acceptedOutputModes, historyLength, pushNotificationConfig, returnImmediately);
    }

    @Override
    public String toString() {
        return "MessageSendConfiguration{" + "acceptedOutputModes=" + acceptedOutputModes + ", historyLength="
                + historyLength + ", pushNotificationConfig=" + pushNotificationConfig + ", returnImmediately="
                + returnImmediately + '}';
    }

    /**
     * Builder class for MessageSendConfiguration.
     */
    public static class Builder {

        private List<String> acceptedOutputModes;

        private Integer historyLength;

        private PushNotificationConfig pushNotificationConfig;

        private Boolean returnImmediately;

        private Builder() {
        }

        public Builder acceptedOutputModes(List<String> acceptedOutputModes) {
            this.acceptedOutputModes = acceptedOutputModes;
            return this;
        }

        public Builder historyLength(Integer historyLength) {
            this.historyLength = historyLength;
            return this;
        }

        public Builder pushNotificationConfig(PushNotificationConfig pushNotificationConfig) {
            this.pushNotificationConfig = pushNotificationConfig;
            return this;
        }

        public Builder blocking(Boolean blocking) {
            this.returnImmediately = blocking == null ? null : !blocking;
            return this;
        }

        public Builder returnImmediately(Boolean returnImmediately) {
            this.returnImmediately = returnImmediately;
            return this;
        }

        public MessageSendConfiguration build() {
            MessageSendConfiguration configuration = new MessageSendConfiguration(acceptedOutputModes, historyLength,
                    pushNotificationConfig, null);
            configuration.setReturnImmediately(returnImmediately);
            return configuration;
        }
    }

}
