package org.nocodenation.nifi.oauthtokenbroker;

import java.time.Instant;

/**
 * Represents an OAuth 2.0 access token with associated metadata.
 * <p>
 * This class encapsulates the core components of an OAuth 2.0 access token response including:
 * <ul>
 *   <li>The access token string itself</li>
 *   <li>Token type (e.g., "Bearer")</li>
 *   <li>Expiration information</li>
 *   <li>Refresh token (when available)</li>
 *   <li>Scope information</li>
 * </ul>
 * <p>
 * The class provides methods to manage token expiration and generate properly formatted
 * authorization header values for use in HTTP requests.
 * <p>
 * This implementation is designed to work with standard OAuth 2.0 providers and follows
 * the specifications defined in RFC 6749.
 */
public class AccessToken {
    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private String refreshToken;
    private String scope;

    /**
     * Default constructor for creating an empty AccessToken instance.
     * <p>
     * Properties must be set using the appropriate setter methods before the token can be used.
     */
    public AccessToken() {
        // Default constructor
    }

    /**
     * Gets the access token string.
     * 
     * @return The OAuth 2.0 access token string
     */
    public String getAccessToken() {
        return this.accessToken;
    }

    /**
     * Sets the access token string.
     * 
     * @param accessToken The OAuth 2.0 access token string
     */
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    /**
     * Gets the token type.
     * <p>
     * Typically "Bearer" for most OAuth 2.0 implementations.
     * 
     * @return The token type (e.g., "Bearer")
     */
    public String getTokenType() {
        return this.tokenType;
    }

    /**
     * Sets the token type.
     * 
     * @param tokenType The token type (e.g., "Bearer")
     */
    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    /**
     * Gets the expiration timestamp of the access token.
     * 
     * @return An Instant representing when the token will expire
     */
    public Instant getExpiresAt() {
        return this.expiresAt;
    }

    /**
     * Sets the expiration timestamp of the access token.
     * 
     * @param expiresAt An Instant representing when the token will expire
     */
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /**
     * Calculates the remaining lifetime of the token in seconds.
     * <p>
     * This method computes the difference between the token's expiration time
     * and the current time.
     * 
     * @return The number of seconds until the token expires
     */
    public long getExpiresIn() {
        return this.expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
    }
    
    /**
     * Sets the expiration time based on the number of seconds from now.
     * <p>
     * This method sets the expiration time to be the current time plus the specified
     * number of seconds, minus a 30-second buffer to account for network latency and
     * clock skew.
     * 
     * @param expiresInSeconds Number of seconds until the token expires
     */
    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresAt = Instant.now().plusSeconds(expiresInSeconds).minusSeconds(30);
    }
    
    /**
     * Sets the expiration time based on a timestamp in milliseconds since epoch.
     * 
     * @param expiresAtMillis Expiration time in milliseconds since epoch
     */
    public void setExpiresAtMillis(long expiresAtMillis) {
        this.expiresAt = Instant.ofEpochMilli(expiresAtMillis);
    }

    /**
     * Gets the refresh token associated with this access token.
     * <p>
     * The refresh token can be used to obtain a new access token when the current one expires,
     * without requiring the user to re-authenticate.
     * 
     * @return The refresh token string, or null if no refresh token was provided
     */
    public String getRefreshToken() {
        return this.refreshToken;
    }

    /**
     * Sets the refresh token associated with this access token.
     * 
     * @param refreshToken The refresh token string
     */
    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    /**
     * Gets the scope(s) associated with this access token.
     * <p>
     * The scope defines the level of access granted by this token.
     * Multiple scopes are typically represented as a space-delimited string.
     * 
     * @return The scope string, or null if no scope was specified
     */
    public String getScope() {
        return this.scope;
    }

    /**
     * Sets the scope(s) associated with this access token.
     * 
     * @param scope The scope string (space-delimited for multiple scopes)
     */
    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * Determines if the token is expired or will expire within the specified refresh window.
     * <p>
     * This method is useful for proactively refreshing tokens before they expire to ensure
     * continuous operation of services that depend on the token.
     *
     * @param refreshWindowSeconds Time in seconds before expiration that the token should be refreshed
     * @return true if the token is expired or will expire within the refresh window, false otherwise
     */
    public boolean isExpiredOrExpiring(long refreshWindowSeconds) {
        // If expiresAt is null (not set), consider the token as not expired
        if (this.expiresAt == null) {
            return false;
        }
        
        Instant refreshThreshold = Instant.now().plusSeconds(refreshWindowSeconds);
        return this.expiresAt.isBefore(refreshThreshold);
    }

    /**
     * Returns a properly formatted authorization header value for use in HTTP requests.
     * <p>
     * The format follows the OAuth 2.0 specification: "{token_type} {access_token}".
     * For example: "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
     *
     * @return String containing the complete Authorization header value
     */
    public String getAuthorizationHeaderValue() {
        return this.tokenType + " " + this.accessToken;
    }
}
