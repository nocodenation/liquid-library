package com.liquid.library.nifi.service;

import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;

@Tags({"browser", "playwright", "web", "automation", "gemini"})
@CapabilityDescription("A Controller Service that manages persistent Playwright browser sessions and allows for natural language control via Gemini.")
public interface WebPawnService extends ControllerService {

    /**
     * Starts a new browser session or retrieves an existing one.
     * @param sessionId User-provided session identifier.
     * @param startUrl Initial URL (optional).
     * @return true if session created/active.
     */
    boolean ensureSession(String sessionId, String startUrl);

    /**
     * Executes a natural language instruction using Gemini.
     * @param sessionId The active session.
     * @param prompt The user's instruction (e.g., "Click login").
     * @return ExecutionResult containing the action taken and screenshot.
     */
    ExecutionResult executePrompt(String sessionId, String prompt);

    /**
     * Directly navigates to a URL.
     * 
     * @param sessionId The active session.
     * @param url The URL to navigate to.
     */
    void navigate(String sessionId, String url);

    /**
     * Returns the current screenshot as Base64.
     * 
     * @param sessionId The active session.
     * @return Base64 encoded screenshot string.
     */
    String getScreenshot(String sessionId);

    /**
     * Gracefully closes the session.
     * 
     * @param sessionId The active session.
     */
    /**
     * Executes a prompt with Gemini using a specific model.
     * @param sessionId The session ID (can be null if stateless, but context is usually tied to session)
     * @param prompt The text prompt
     * @param imageBase64 Base64 encoded image (optional)
     * @param modelName The model to use (e.g. gemini-1.5-flash)
     * @return The text response from Gemini
     */
    String askGemini(String sessionId, String prompt, String imageBase64, String modelName);

    void closeSession(String sessionId);
}
