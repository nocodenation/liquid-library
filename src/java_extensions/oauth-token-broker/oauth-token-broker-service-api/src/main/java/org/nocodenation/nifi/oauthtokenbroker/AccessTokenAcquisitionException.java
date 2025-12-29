package org.nocodenation.nifi.oauthtokenbroker;

/**
 * Exception thrown when there's an issue acquiring or refreshing an OAuth 2.0 access token.
 * <p>
 * This exception is used to encapsulate various errors that can occur during the OAuth 2.0
 * token acquisition process, including but not limited to:
 * <ul>
 *   <li>Network connectivity issues when contacting the OAuth provider</li>
 *   <li>Authentication failures (invalid client credentials)</li>
 *   <li>Authorization failures (invalid or expired refresh tokens)</li>
 *   <li>Malformed responses from the OAuth provider</li>
 *   <li>Rate limiting or service unavailability at the OAuth provider</li>
 * </ul>
 * <p>
 * This exception provides context about the failure through its message and can optionally
 * wrap the underlying cause exception.
 * <p>
 * This exception is part of the OAuth Token Broker API and is used across the service
 * and processor components.
 */
public class AccessTokenAcquisitionException extends Exception {

    /**
     * Constructs a new exception with the specified detail message.
     * <p>
     * This constructor is typically used when the cause of the exception is unknown
     * or when there is no underlying exception.
     *
     * @param message A description of the error that occurred during token acquisition
     */
    public AccessTokenAcquisitionException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     * <p>
     * This constructor is typically used to wrap lower-level exceptions that occurred
     * during the token acquisition process, such as network exceptions, JSON parsing
     * exceptions, or HTTP client exceptions.
     *
     * @param message A description of the error that occurred during token acquisition
     * @param cause The underlying exception that caused this exception to be thrown
     */
    public AccessTokenAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
