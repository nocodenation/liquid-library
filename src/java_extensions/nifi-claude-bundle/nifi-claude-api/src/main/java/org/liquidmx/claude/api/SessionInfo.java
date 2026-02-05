/*
 * Session Info Data Object
 */
package org.liquidmx.claude.api;

import java.time.Instant;

/**
 * Information about a Claude session.
 */
public class SessionInfo {

    private final String sessionKey;
    private final String sessionId;
    private final ToolMode toolMode;
    private final String workingDirectory;
    private final Instant createdAt;
    private final Instant lastActiveAt;
    private final int promptCount;
    private final SessionState state;

    public SessionInfo(
            String sessionKey,
            String sessionId,
            ToolMode toolMode,
            String workingDirectory,
            Instant createdAt,
            Instant lastActiveAt,
            int promptCount,
            SessionState state) {
        this.sessionKey = sessionKey;
        this.sessionId = sessionId;
        this.toolMode = toolMode;
        this.workingDirectory = workingDirectory;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.promptCount = promptCount;
        this.state = state;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ToolMode getToolMode() {
        return toolMode;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public int getPromptCount() {
        return promptCount;
    }

    public SessionState getState() {
        return state;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionKey;
        private String sessionId;
        private ToolMode toolMode = ToolMode.READ_ONLY;
        private String workingDirectory;
        private Instant createdAt = Instant.now();
        private Instant lastActiveAt = Instant.now();
        private int promptCount = 0;
        private SessionState state = SessionState.ACTIVE;

        public Builder sessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder toolMode(ToolMode toolMode) {
            this.toolMode = toolMode;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastActiveAt(Instant lastActiveAt) {
            this.lastActiveAt = lastActiveAt;
            return this;
        }

        public Builder promptCount(int promptCount) {
            this.promptCount = promptCount;
            return this;
        }

        public Builder state(SessionState state) {
            this.state = state;
            return this;
        }

        public SessionInfo build() {
            return new SessionInfo(
                sessionKey, sessionId, toolMode, workingDirectory,
                createdAt, lastActiveAt, promptCount, state);
        }
    }
}
