package org.nocodenation.nifi.authenticationbroker;

import org.apache.nifi.controller.ControllerService;

/**
 * OAuth2 Authentication Broker Service
 *
 * Provides OAuth2-based authentication capabilities for accessing third-party APIs
 * (Google, Microsoft). Manages the complete OAuth2 Authorization Code Flow with PKCE,
 * maintains access tokens, handles token refresh, and provides a web interface for
 * user authentication.
 */
public interface AuthenticationBrokerService extends ControllerService {

    /**
     * Get a valid access token. If the token is expired or about to expire,
     * it will be automatically refreshed.
     *
     * @return Valid access token
     * @throws TokenExpiredException if token cannot be refreshed
     */
    String getAccessToken() throws TokenExpiredException;

    /**
     * Check if the service is authenticated and has valid credentials
     *
     * @return true if authenticated, false otherwise
     */
    boolean isAuthenticated();

    /**
     * Get the email/username of the authenticated user
     *
     * @return Authenticated user identifier, or null if not authenticated
     */
    String getAuthenticatedUser();

    /**
     * Get the token expiration timestamp in milliseconds since epoch
     *
     * @return Token expiration time, or 0 if not authenticated
     */
    long getTokenExpirationTime();

    /**
     * Manually refresh the access token
     *
     * @return New access token
     * @throws AuthenticationException if refresh fails
     */
    String refreshToken() throws AuthenticationException;

    /**
     * Revoke the current authentication and clear stored tokens
     */
    void revokeAuthentication();

    /**
     * Exception thrown when token is expired and cannot be refreshed
     */
    class TokenExpiredException extends Exception {
        public TokenExpiredException(String message) {
            super(message);
        }

        public TokenExpiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when authentication or token operations fail
     */
    class AuthenticationException extends Exception {
        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
