/*
 * Claude Service Exception
 */
package org.liquidmx.claude.api;

/**
 * Exception thrown when Claude Code service operations fail.
 */
public class ClaudeServiceException extends Exception {

    public ClaudeServiceException(String message) {
        super(message);
    }

    public ClaudeServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
