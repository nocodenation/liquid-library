/*
 * Tool Use Data Object
 */
package org.liquidmx.claude.api;

import java.util.Map;

/**
 * Represents a tool invocation during Claude execution.
 */
public class ToolUse {

    private final String tool;
    private final Map<String, Object> input;

    public ToolUse(String tool, Map<String, Object> input) {
        this.tool = tool;
        this.input = input;
    }

    /**
     * Name of the tool that was invoked.
     */
    public String getTool() {
        return tool;
    }

    /**
     * Input parameters passed to the tool.
     */
    public Map<String, Object> getInput() {
        return input;
    }
}
