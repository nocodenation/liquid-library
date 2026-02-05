/*
 * Tool Mode Enumeration
 */
package org.liquidmx.claude.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Defines the tools available to Claude in a session.
 */
public enum ToolMode {

    /**
     * Read-only access: Read, Glob, Grep.
     * Safe for analysis without modifications.
     */
    READ_ONLY(Arrays.asList("Read", "Glob", "Grep"), 1),

    /**
     * File access: Read, Glob, Grep, Edit, Write.
     * Can modify files in the working directory.
     */
    FILE_ACCESS(Arrays.asList("Read", "Glob", "Grep", "Edit", "Write"), 2),

    /**
     * Full access: All tools including Bash.
     * Complete autonomy including command execution.
     */
    FULL(Arrays.asList("Read", "Glob", "Grep", "Edit", "Write", "Bash", "WebSearch", "WebFetch", "Task"), 3);

    private final List<String> tools;
    private final int level;

    ToolMode(List<String> tools, int level) {
        this.tools = Collections.unmodifiableList(tools);
        this.level = level;
    }

    /**
     * Get the list of tools available in this mode.
     */
    public List<String> getTools() {
        return tools;
    }

    /**
     * Get the permission level (higher = more access).
     */
    public int getLevel() {
        return level;
    }

    /**
     * Check if this mode can escalate to another mode.
     * Escalation is only allowed to modes with equal or lower permission level.
     */
    public boolean canEscalateTo(ToolMode other) {
        return this.level >= other.level;
    }

    /**
     * Parse tool mode from string, case-insensitive.
     */
    public static ToolMode fromString(String value) {
        if (value == null || value.isEmpty()) {
            return READ_ONLY;
        }
        try {
            return valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return READ_ONLY;
        }
    }
}
