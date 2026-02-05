/*
 * Prompt Options Data Object
 */
package org.liquidmx.claude.api;

import java.util.List;
import java.util.Map;

/**
 * Optional configuration for prompt execution.
 */
public class PromptOptions {

    private ToolMode toolModeOverride;
    private List<String> allowedTools;
    private String workingDirectory;
    private String systemPromptAppend;
    private Integer maxTurns;
    private Map<String, String> metadata;

    private PromptOptions(Builder builder) {
        this.toolModeOverride = builder.toolModeOverride;
        this.allowedTools = builder.allowedTools;
        this.workingDirectory = builder.workingDirectory;
        this.systemPromptAppend = builder.systemPromptAppend;
        this.maxTurns = builder.maxTurns;
        this.metadata = builder.metadata;
    }

    public ToolMode getToolModeOverride() {
        return toolModeOverride;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public String getSystemPromptAppend() {
        return systemPromptAppend;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ToolMode toolModeOverride;
        private List<String> allowedTools;
        private String workingDirectory;
        private String systemPromptAppend;
        private Integer maxTurns;
        private Map<String, String> metadata;

        public Builder toolModeOverride(ToolMode toolMode) {
            this.toolModeOverride = toolMode;
            return this;
        }

        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
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

        public Builder maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public PromptOptions build() {
            return new PromptOptions(this);
        }
    }
}
