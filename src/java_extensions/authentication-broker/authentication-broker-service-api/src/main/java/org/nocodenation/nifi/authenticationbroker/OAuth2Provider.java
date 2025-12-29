package org.nocodenation.nifi.authenticationbroker;

/**
 * Supported OAuth2 Authentication Providers
 */
public enum OAuth2Provider {
    GOOGLE("Google",
           "https://accounts.google.com/o/oauth2/v2/auth",
           "https://oauth2.googleapis.com/token",
           "https://oauth2.googleapis.com/revoke"),

    MICROSOFT("Microsoft",
              "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
              "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
              null);

    private final String displayName;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String revocationEndpoint;

    OAuth2Provider(String displayName, String authorizationEndpoint,
                   String tokenEndpoint, String revocationEndpoint) {
        this.displayName = displayName;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.revocationEndpoint = revocationEndpoint;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public String getAuthorizationEndpoint(String tenant) {
        return authorizationEndpoint.replace("{tenant}", tenant != null ? tenant : "common");
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public String getTokenEndpoint(String tenant) {
        return tokenEndpoint.replace("{tenant}", tenant != null ? tenant : "common");
    }

    public String getRevocationEndpoint() {
        return revocationEndpoint;
    }

    public boolean supportsRevocation() {
        return revocationEndpoint != null;
    }
}
