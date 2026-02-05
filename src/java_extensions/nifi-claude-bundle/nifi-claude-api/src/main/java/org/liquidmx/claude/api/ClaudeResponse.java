/*
 * Claude Response Data Object
 */
package org.liquidmx.claude.api;

import java.util.List;
import java.util.Map;

/**
 * Response from a Claude prompt execution.
 */
public class ClaudeResponse {

    private final String result;
    private final String sessionKey;
    private final String sessionId;
    private final List<ToolUse> toolsUsed;
    private final int turnsUsed;
    private final long durationMs;
    private final Map<String, String> metadata;

    public ClaudeResponse(
            String result,
            String sessionKey,
            String sessionId,
            List<ToolUse> toolsUsed,
            int turnsUsed,
            long durationMs,
            Map<String, String> metadata) {
        this.result = result;
        this.sessionKey = sessionKey;
        this.sessionId = sessionId;
        this.toolsUsed = toolsUsed;
        this.turnsUsed = turnsUsed;
        this.durationMs = durationMs;
        this.metadata = metadata;
    }

    /**
     * Claude's response text.
     */
    public String getResult() {
        return result;
    }

    /**
     * Session key used for this prompt.
     */
    public String getSessionKey() {
        return sessionKey;
    }

    /**
     * Internal Claude session ID.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Tools invoked during execution.
     */
    public List<ToolUse> getToolsUsed() {
        return toolsUsed;
    }

    /**
     * Number of agent turns taken.
     */
    public int getTurnsUsed() {
        return turnsUsed;
    }

    /**
     * Processing time in milliseconds.
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Pass-through metadata.
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Builder for ClaudeResponse.
     */
    public static class Builder {
        private String result = "";
        private String sessionKey = "";
        private String sessionId = "";
        private List<ToolUse> toolsUsed = List.of();
        private int turnsUsed = 0;
        private long durationMs = 0;
        private Map<String, String> metadata = Map.of();

        public Builder result(String result) {
            this.result = result;
            return this;
        }

        public Builder sessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder toolsUsed(List<ToolUse> toolsUsed) {
            this.toolsUsed = toolsUsed;
            return this;
        }

        public Builder turnsUsed(int turnsUsed) {
            this.turnsUsed = turnsUsed;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ClaudeResponse build() {
            return new ClaudeResponse(
                result, sessionKey, sessionId, toolsUsed, turnsUsed, durationMs, metadata);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
