/*
 * Session Configuration Data Object
 */
package org.liquidmx.claude.api;

import java.util.Map;

/**
 * Configuration for creating a new Claude session.
 */
public class SessionConfig {

    private final ToolMode toolMode;
    private final String workingDirectory;
    private final String systemPromptAppend;
    private final Map<String, String> initialContext;

    private SessionConfig(Builder builder) {
        this.toolMode = builder.toolMode;
        this.workingDirectory = builder.workingDirectory;
        this.systemPromptAppend = builder.systemPromptAppend;
        this.initialContext = builder.initialContext;
    }

    public ToolMode getToolMode() {
        return toolMode;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public String getSystemPromptAppend() {
        return systemPromptAppend;
    }

    public Map<String, String> getInitialContext() {
        return initialContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ToolMode toolMode = ToolMode.READ_ONLY;
        private String workingDirectory;
        private String systemPromptAppend;
        private Map<String, String> initialContext;

        public Builder toolMode(ToolMode toolMode) {
            this.toolMode = toolMode;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder systemPromptAppend(String systemPromptAppend) {
            this.systemPromptAppend = systemPromptAppend;
            return this;
        }

        public Builder initialContext(Map<String, String> initialContext) {
            this.initialContext = initialContext;
            return this;
        }

        public SessionConfig build() {
            return new SessionConfig(this);
        }
    }
}
