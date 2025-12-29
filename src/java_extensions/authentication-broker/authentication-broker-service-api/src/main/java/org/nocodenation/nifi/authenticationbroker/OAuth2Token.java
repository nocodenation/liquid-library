package org.nocodenation.nifi.authenticationbroker;

import java.io.Serializable;

/**
 * Data class representing OAuth2 tokens and associated metadata
 */
public class OAuth2Token implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String accessToken;
    private final String refreshToken;
    private final long expiresAt;
    private final String scope;
    private final String authenticatedUser;

    public OAuth2Token(String accessToken, String refreshToken, long expiresAt,
                       String scope, String authenticatedUser) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.scope = scope;
        this.authenticatedUser = authenticatedUser;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public String getScope() {
        return scope;
    }

    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public boolean isExpiringSoon(int bufferSeconds) {
        return System.currentTimeMillis() >= (expiresAt - (bufferSeconds * 1000L));
    }

    @Override
    public String toString() {
        return "OAuth2Token{" +
                "authenticatedUser='" + authenticatedUser + '\'' +
                ", expiresAt=" + expiresAt +
                ", scope='" + scope + '\'' +
                '}';
    }
}
