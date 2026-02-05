/*
 * Service Metrics Data Object
 */
package org.liquidmx.claude.api;

import java.util.Map;

/**
 * Metrics about the Claude Code service.
 */
public class ServiceMetrics {

    private final int activeSessionCount;
    private final int totalPromptsProcessed;
    private final double averageResponseTimeMs;
    private final Map<String, Integer> promptsPerSession;

    public ServiceMetrics(
            int activeSessionCount,
            int totalPromptsProcessed,
            double averageResponseTimeMs,
            Map<String, Integer> promptsPerSession) {
        this.activeSessionCount = activeSessionCount;
        this.totalPromptsProcessed = totalPromptsProcessed;
        this.averageResponseTimeMs = averageResponseTimeMs;
        this.promptsPerSession = promptsPerSession;
    }

    public int getActiveSessionCount() {
        return activeSessionCount;
    }

    public int getTotalPromptsProcessed() {
        return totalPromptsProcessed;
    }

    public double getAverageResponseTimeMs() {
        return averageResponseTimeMs;
    }

    public Map<String, Integer> getPromptsPerSession() {
        return promptsPerSession;
    }
}
