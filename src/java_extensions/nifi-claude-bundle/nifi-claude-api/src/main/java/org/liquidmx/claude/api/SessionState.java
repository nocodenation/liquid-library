/*
 * Session State Enumeration
 */
package org.liquidmx.claude.api;

/**
 * Possible states of a Claude session.
 */
public enum SessionState {
    /**
     * Session is active and ready to receive prompts.
     */
    ACTIVE,

    /**
     * Session is idle (no recent activity but not expired).
     */
    IDLE,

    /**
     * Session has expired due to timeout.
     */
    EXPIRED
}
