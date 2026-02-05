/*
 * Claude Code Controller Service Interface
 *
 * Defines the contract for Claude Code integration with Apache NiFi.
 */
package org.liquidmx.claude.api;

import org.apache.nifi.controller.ControllerService;

import java.util.List;
import java.util.Map;

/**
 * Controller Service interface for Claude Code integration.
 *
 * <p>This service manages Claude Code sessions, skills, and prompt execution.
 * It communicates with the Claude NiFi Bridge (Python) via HTTP.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * ClaudeCodeService service = context.getProperty(CLAUDE_SERVICE)
 *     .asControllerService(ClaudeCodeService.class);
 *
 * ClaudeResponse response = service.prompt("session-key", "Analyze this code", null);
 * </pre>
 */
public interface ClaudeCodeService extends ControllerService {

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE PROMPTING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Execute a prompt in a Claude session.
     *
     * <p>Creates the session if it doesn't exist. Session context is preserved
     * across prompts with the same session key.</p>
     *
     * @param sessionKey Unique key for the session (supports dynamic keying)
     * @param prompt The prompt to send to Claude
     * @param options Optional configuration overrides (tool mode, working directory, etc.)
     * @return Response containing result, session info, and metadata
     * @throws ClaudeServiceException if the prompt execution fails
     */
    ClaudeResponse prompt(String sessionKey, String prompt, PromptOptions options)
        throws ClaudeServiceException;

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Explicitly create a session with specific configuration.
     *
     * <p>Useful when you want to pre-configure before the first prompt.</p>
     *
     * @param sessionKey Unique key for the session
     * @param config Session configuration
     * @return Information about the created session
     * @throws ClaudeServiceException if session creation fails
     */
    SessionInfo createSession(String sessionKey, SessionConfig config)
        throws ClaudeServiceException;

    /**
     * Close a session, freeing resources.
     *
     * @param sessionKey Session to close
     * @throws ClaudeServiceException if session closure fails
     */
    void closeSession(String sessionKey) throws ClaudeServiceException;

    /**
     * List all active sessions managed by this service.
     *
     * @return List of session information
     * @throws ClaudeServiceException if listing fails
     */
    List<SessionInfo> listSessions() throws ClaudeServiceException;

    /**
     * Get information about a specific session.
     *
     * @param sessionKey Session to query
     * @return Session information, or null if not found
     * @throws ClaudeServiceException if query fails
     */
    SessionInfo getSession(String sessionKey) throws ClaudeServiceException;

    /**
     * Check if a session exists and is active.
     *
     * @param sessionKey Session to check
     * @return true if session exists
     * @throws ClaudeServiceException if check fails
     */
    boolean sessionExists(String sessionKey) throws ClaudeServiceException;

    // ═══════════════════════════════════════════════════════════════════════════
    // SKILL MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add a skill that will be available to all sessions.
     *
     * @param skillName Name of the skill (used as identifier)
     * @param skillContent Markdown content defining the skill (SKILL.md format)
     * @throws ClaudeServiceException if skill addition fails
     */
    void addSkill(String skillName, String skillContent) throws ClaudeServiceException;

    /**
     * Remove a skill.
     *
     * @param skillName Skill to remove
     * @throws ClaudeServiceException if skill removal fails
     */
    void removeSkill(String skillName) throws ClaudeServiceException;

    /**
     * List all available skills.
     *
     * @return List of skill information
     * @throws ClaudeServiceException if listing fails
     */
    List<SkillInfo> listSkills() throws ClaudeServiceException;

    /**
     * Get the content of a specific skill.
     *
     * @param skillName Skill to retrieve
     * @return Skill content, or null if not found
     * @throws ClaudeServiceException if retrieval fails
     */
    String getSkillContent(String skillName) throws ClaudeServiceException;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update a runtime configuration value.
     *
     * @param key Configuration key
     * @param value New value
     * @throws ClaudeServiceException if update fails
     */
    void updateConfig(String key, String value) throws ClaudeServiceException;

    /**
     * Get current configuration.
     *
     * @return Map of configuration key-value pairs
     * @throws ClaudeServiceException if retrieval fails
     */
    Map<String, String> getConfig() throws ClaudeServiceException;

    // ═══════════════════════════════════════════════════════════════════════════
    // HEALTH & METRICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if the bridge service is healthy and responsive.
     *
     * @return true if healthy
     */
    boolean isHealthy();

    /**
     * Get service metrics.
     *
     * @return Service metrics
     * @throws ClaudeServiceException if retrieval fails
     */
    ServiceMetrics getMetrics() throws ClaudeServiceException;
}
