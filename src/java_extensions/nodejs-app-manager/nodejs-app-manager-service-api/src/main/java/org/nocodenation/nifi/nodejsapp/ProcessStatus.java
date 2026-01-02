/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nocodenation.nifi.nodejsapp;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the current status of a managed Node.js application process.
 *
 * This immutable DTO contains comprehensive status information including process
 * state, health check results, and operational metrics.
 */
public class ProcessStatus {

    /**
     * Process lifecycle states.
     */
    public enum State {
        /** Process is not running */
        STOPPED,

        /** Process is in the startup phase */
        STARTING,

        /** Process is running and healthy */
        RUNNING,

        /** Process is running but health checks are failing */
        UNHEALTHY,

        /** Process is shutting down */
        STOPPING,

        /** Process has crashed or exited unexpectedly */
        CRASHED
    }

    private final State state;
    private final Long processId;
    private final Instant startTime;
    private final Instant lastHealthCheck;
    private final boolean healthCheckPassing;
    private final String healthCheckMessage;
    private final int restartCount;
    private final String applicationVersion;

    private ProcessStatus(Builder builder) {
        this.state = Objects.requireNonNull(builder.state, "State cannot be null");
        this.processId = builder.processId;
        this.startTime = builder.startTime;
        this.lastHealthCheck = builder.lastHealthCheck;
        this.healthCheckPassing = builder.healthCheckPassing;
        this.healthCheckMessage = builder.healthCheckMessage;
        this.restartCount = builder.restartCount;
        this.applicationVersion = builder.applicationVersion;
    }

    public State getState() {
        return state;
    }

    public Long getProcessId() {
        return processId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getLastHealthCheck() {
        return lastHealthCheck;
    }

    public boolean isHealthCheckPassing() {
        return healthCheckPassing;
    }

    public String getHealthCheckMessage() {
        return healthCheckMessage;
    }

    public int getRestartCount() {
        return restartCount;
    }

    public String getApplicationVersion() {
        return applicationVersion;
    }

    /**
     * Creates a new Builder for constructing ProcessStatus instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ProcessStatus instances.
     */
    public static class Builder {
        private State state;
        private Long processId;
        private Instant startTime;
        private Instant lastHealthCheck;
        private boolean healthCheckPassing;
        private String healthCheckMessage;
        private int restartCount;
        private String applicationVersion;

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder processId(Long processId) {
            this.processId = processId;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder lastHealthCheck(Instant lastHealthCheck) {
            this.lastHealthCheck = lastHealthCheck;
            return this;
        }

        public Builder healthCheckPassing(boolean healthCheckPassing) {
            this.healthCheckPassing = healthCheckPassing;
            return this;
        }

        public Builder healthCheckMessage(String healthCheckMessage) {
            this.healthCheckMessage = healthCheckMessage;
            return this;
        }

        public Builder restartCount(int restartCount) {
            this.restartCount = restartCount;
            return this;
        }

        public Builder applicationVersion(String applicationVersion) {
            this.applicationVersion = applicationVersion;
            return this;
        }

        public ProcessStatus build() {
            return new ProcessStatus(this);
        }
    }

    @Override
    public String toString() {
        return "ProcessStatus{" +
                "state=" + state +
                ", processId=" + processId +
                ", startTime=" + startTime +
                ", lastHealthCheck=" + lastHealthCheck +
                ", healthCheckPassing=" + healthCheckPassing +
                ", healthCheckMessage='" + healthCheckMessage + '\'' +
                ", restartCount=" + restartCount +
                ", applicationVersion='" + applicationVersion + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessStatus that = (ProcessStatus) o;
        return healthCheckPassing == that.healthCheckPassing &&
                restartCount == that.restartCount &&
                state == that.state &&
                Objects.equals(processId, that.processId) &&
                Objects.equals(startTime, that.startTime) &&
                Objects.equals(lastHealthCheck, that.lastHealthCheck) &&
                Objects.equals(healthCheckMessage, that.healthCheckMessage) &&
                Objects.equals(applicationVersion, that.applicationVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, processId, startTime, lastHealthCheck,
                healthCheckPassing, healthCheckMessage, restartCount, applicationVersion);
    }
}