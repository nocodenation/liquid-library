# AuthenticationBrokerService Specification

## Overview

The **AuthenticationBrokerService** is a NiFi Controller Service that provides OAuth2-based authentication capabilities for accessing third-party APIs (Google, Microsoft). It manages the complete OAuth2 Authorization Code Flow with PKCE, maintains access tokens, handles token refresh, and provides a web interface for user authentication.

## Purpose

This service acts as a centralized authentication broker that:
- Manages OAuth2 authentication flows for multiple providers
- Provides valid access tokens to NiFi processors
- Handles automatic token refresh before expiration
- Offers a web-based user interface for login/logout operations
- Stores authentication state securely

## Supported Providers

1. **Google** - For accessing Google APIs (Sheets, Drive, Gmail, etc.)
2. **Microsoft** - For accessing Microsoft Graph API (Excel, OneDrive, Teams, etc.)

## Configuration Properties

### General Configuration

| Property Name | Type | Required | Default | Description |
|--------------|------|----------|---------|-------------|
| Authentication Provider | Enumeration | Yes | - | Select the OAuth2 provider: Google or Microsoft |
| Web Server Port | Integer | Yes | 8888 | Port for the authentication web interface |
| Redirect URI | String | No | Auto-generated | OAuth2 redirect URI (auto-generated as `http://localhost:{port}/callback`) |

### Google Provider Configuration

| Property Name | Type | Required | Default | Description |
|--------------|------|----------|---------|-------------|
| Google Client ID | String | Yes (if Google) | - | OAuth2 Client ID from Google Cloud Console |
| Google Client Secret | Sensitive | Yes (if Google) | - | OAuth2 Client Secret from Google Cloud Console |
| Google Scopes | String | Yes (if Google) | - | Space-separated OAuth2 scopes (e.g., `https://www.googleapis.com/auth/spreadsheets`) |

### Microsoft Provider Configuration

| Property Name | Type | Required | Default | Description |
|--------------|------|----------|---------|-------------|
| Microsoft Client ID | String | Yes (if Microsoft) | - | Application (client) ID from Azure AD |
| Microsoft Client Secret | Sensitive | Yes (if Microsoft) | - | Client secret from Azure AD |
| Microsoft Tenant ID | String | Yes (if Microsoft) | common | Azure AD tenant ID (or 'common' for multi-tenant) |
| Microsoft Scopes | String | Yes (if Microsoft) | - | Space-separated OAuth2 scopes (e.g., `https://graph.microsoft.com/Files.ReadWrite`) |

### Advanced Configuration

| Property Name | Type | Required | Default | Description |
|--------------|------|----------|---------|-------------|
| Token Refresh Buffer | Integer | No | 300 | Seconds before expiration to refresh token automatically |
| Enable PKCE | Boolean | No | true | Enable Proof Key for Code Exchange for enhanced security |

## Service Interface

```java
package com.example.nifi.services;

import org.apache.nifi.controller.ControllerService;

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
     * @return Authenticated user identifier
     */
    String getAuthenticatedUser();

    /**
     * Get the token expiration timestamp in milliseconds since epoch
     *
     * @return Token expiration time
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
}
```

## Complete OAuth2 Workflow

### 1. Initial Authentication (Login)

**User Action**: User navigates to `http://localhost:{port}/` in their browser

**Service Actions**:
1. Generate random `state` parameter (CSRF protection)
2. Generate PKCE `code_verifier` (if enabled)
3. Generate PKCE `code_challenge` from verifier
4. Store state and code_verifier in session
5. Build authorization URL with parameters:
   - `client_id`: Configured client ID
   - `redirect_uri`: `http://localhost:{port}/callback`
   - `response_type`: `code`
   - `scope`: Configured scopes
   - `state`: Generated state value
   - `code_challenge`: PKCE challenge (if enabled)
   - `code_challenge_method`: `S256` (if enabled)
   - `access_type`: `offline` (Google - for refresh token)
   - `prompt`: `consent` (Google - to ensure refresh token)
6. Redirect user to provider's authorization URL

**Provider URLs**:
- Google: `https://accounts.google.com/o/oauth2/v2/auth`
- Microsoft: `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize`

### 2. User Authorization

**User Action**: User logs in to their Google/Microsoft account and grants permissions

**Provider Action**: Provider redirects back to `http://localhost:{port}/callback?code={auth_code}&state={state}`

### 3. Authorization Code Callback

**Service Actions**:
1. Validate `state` parameter matches stored value (CSRF check)
2. Extract `code` parameter
3. Retrieve stored `code_verifier` from session
4. Exchange authorization code for tokens via POST to token endpoint:
   - `client_id`: Configured client ID
   - `client_secret`: Configured client secret
   - `code`: Authorization code from callback
   - `redirect_uri`: Must match the original redirect URI
   - `grant_type`: `authorization_code`
   - `code_verifier`: PKCE verifier (if enabled)

**Token Endpoint URLs**:
- Google: `https://oauth2.googleapis.com/token`
- Microsoft: `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token`

**Response** (JSON):
```json
{
  "access_token": "ya29.a0AfH6...",
  "expires_in": 3600,
  "refresh_token": "1//0gZ...",
  "scope": "https://www.googleapis.com/auth/spreadsheets",
  "token_type": "Bearer"
}
```

5. Store tokens securely using NiFi StateManager:
   - `access_token`
   - `refresh_token`
   - `expires_at` (current time + expires_in)
   - `scope`
   - `authenticated_user` (extracted from token or userinfo endpoint)
6. Display success page showing authenticated user
7. Clear session state and code_verifier

### 4. Token Usage (Processor Requests)

**Processor Action**: Calls `getAccessToken()` on the service

**Service Actions**:
1. Check if token exists
2. Check if token is expired or about to expire (within refresh buffer)
3. If expired/expiring, automatically refresh token (see step 5)
4. Return valid access token

**Processor Action**: Uses token in Authorization header:
```
Authorization: Bearer {access_token}
```

### 5. Automatic Token Refresh

**Trigger**: Token expiration time - refresh buffer < current time

**Service Actions**:
1. POST to token endpoint with refresh token:
   - `client_id`: Configured client ID
   - `client_secret`: Configured client secret
   - `refresh_token`: Stored refresh token
   - `grant_type`: `refresh_token`
2. Receive new access token (and possibly new refresh token)
3. Update stored tokens in StateManager
4. Return new access token

### 6. Token Revocation (Logout)

**User Action**: User clicks "Logout" button at `http://localhost:{port}/status`

**Service Actions**:
1. (Optional) Revoke tokens with provider:
   - Google: POST to `https://oauth2.googleapis.com/revoke`
   - Microsoft: Not required, tokens expire automatically
2. Clear all stored tokens from StateManager
3. Clear any in-memory token cache
4. Redirect to login page with logout confirmation

## Web Interface Endpoints

### `GET /` - Login Page
- Display provider name
- Show "Login with {Provider}" button
- If already authenticated, show status and logout option
- Clicking login initiates OAuth2 flow

### `GET /auth/redirect` - Initiate OAuth2 Flow
- Generate state and PKCE parameters
- Store in session
- Redirect to provider authorization URL

### `GET /callback` - OAuth2 Callback Handler
- Validate state parameter
- Exchange authorization code for tokens
- Store tokens
- Redirect to status page

### `GET /status` - Authentication Status
- Display authenticated user email/name
- Show token expiration time
- Show granted scopes
- Provide "Logout" button

### `POST /logout` - Logout Handler
- Revoke tokens
- Clear stored state
- Redirect to login page

## State Management

### NiFi StateManager Storage

The service uses NiFi's StateManager API to persist tokens across NiFi restarts:

```java
StateManager stateManager = context.getStateManager();
StateMap state = stateManager.getState(Scope.LOCAL);

Map<String, String> newState = new HashMap<>();
newState.put("access_token", accessToken);
newState.put("refresh_token", refreshToken);
newState.put("expires_at", String.valueOf(expiresAt));
newState.put("authenticated_user", userEmail);

stateManager.setState(newState, Scope.LOCAL);
```

### Encryption

All sensitive values (access_token, refresh_token) should be encrypted before storage using NiFi's property encryption utilities.

## Security Considerations

### 1. CSRF Protection
- Use cryptographically random `state` parameter
- Validate state in callback matches original request
- State should be single-use and expire after 10 minutes

### 2. PKCE (Proof Key for Code Exchange)
- Use S256 (SHA-256) code challenge method
- Generate cryptographically random code_verifier (43-128 chars)
- Prevents authorization code interception attacks

### 3. Transport Security
- Recommend HTTPS for production deployments
- Redirect URI should use HTTPS in production
- Web interface should enforce secure headers

### 4. Token Storage
- Encrypt tokens before persisting to StateManager
- Never log tokens or secrets
- Clear tokens from memory after use

### 5. Scope Validation
- Request minimal necessary scopes
- Validate received scopes match requested scopes
- Document required scopes for each provider

## Error Handling

### Configuration Errors
- Missing required properties → Display clear error message during validation
- Invalid Client ID/Secret → Catch and report during token exchange
- Invalid scopes → Provider will return error during authorization

### OAuth2 Errors
- User denies consent → Show user-friendly message with retry option
- Invalid authorization code → Log error, clear state, redirect to login
- Token refresh failure → Require re-authentication
- Network errors → Retry with exponential backoff (up to 3 attempts)

### Runtime Errors
- Expired refresh token → Require re-authentication
- Token revoked externally → Detect on next API call, require re-authentication
- StateManager unavailable → Fail gracefully, disable service

## Dependencies (Maven)

```xml
<dependencies>
    <!-- Jetty for web server -->
    <dependency>
        <groupId>org.eclipse.jetty</groupId>
        <artifactId>jetty-server</artifactId>
        <version>12.0.27</version>
    </dependency>
    <dependency>
        <groupId>org.eclipse.jetty.ee10</groupId>
        <artifactId>jetty-ee10-servlet</artifactId>
        <version>12.0.27</version>
    </dependency>

    <!-- HTTP client for OAuth2 calls -->
    <dependency>
        <groupId>org.apache.httpcomponents.client5</groupId>
        <artifactId>httpclient5</artifactId>
        <version>5.2.1</version>
    </dependency>

    <!-- JSON processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>

    <!-- JWT decoding for user info -->
    <dependency>
        <groupId>com.auth0</groupId>
        <artifactId>java-jwt</artifactId>
        <version>4.4.0</version>
    </dependency>
</dependencies>
```

## Workflow Validation Checklist

- [x] User can initiate login through web interface
- [x] State parameter prevents CSRF attacks
- [x] PKCE prevents authorization code interception
- [x] Authorization code is exchanged for access token
- [x] Refresh token is stored for long-term access
- [x] Access token is automatically refreshed before expiration
- [x] Processors can retrieve valid access tokens
- [x] User can view authentication status
- [x] User can revoke authentication (logout)
- [x] Tokens persist across NiFi restarts (StateManager)
- [x] Errors are handled gracefully with user feedback
- [x] Service works for both Google and Microsoft providers

## Potential Issues & Recommendations

### 1. Localhost Redirect URI Limitation
**Issue**: OAuth2 redirect to `http://localhost` only works when browser and NiFi are on same machine.

**Solutions**:
- For development: Use localhost (acceptable)
- For production: Deploy a small web app on public URL that redirects to NiFi
- Alternative: Use device code flow (no redirect needed, but different UX)

### 2. Token Encryption
**Issue**: StateManager stores data in plaintext by default

**Solution**: Use NiFi's `StringEncryptor` utility to encrypt tokens before storage

### 3. Concurrent Token Refresh
**Issue**: Multiple processors might trigger refresh simultaneously

**Solution**: Implement token refresh mutex/lock to ensure only one refresh at a time

### 4. Provider-Specific User Info
**Issue**: Google and Microsoft return user info differently

**Solution**: Create provider-specific adapters to normalize user information

### 5. Browser Requirement
**Issue**: OAuth2 requires user to interact with browser

**Implications**:
- Cannot be fully automated
- User must authenticate at least once
- Refresh tokens allow long-term automation after initial auth

### 6. Refresh Token Expiration
**Issue**: Refresh tokens can expire (Microsoft: 90 days inactive, Google: 6 months)

**Solution**:
- Document refresh token expiration policies
- Monitor and alert when re-authentication is needed
- Consider periodic "heartbeat" refresh to keep tokens active

## Future Enhancements

1. **Multiple Accounts**: Support multiple authenticated accounts simultaneously
2. **Token Sharing**: Allow multiple processor instances to share same token
3. **Additional Providers**: Add support for more OAuth2 providers (Salesforce, Dropbox, etc.)
4. **Device Code Flow**: Alternative auth method for headless/remote deployments
5. **Audit Logging**: Log all authentication events for security monitoring

## Setup Instructions for Providers

### Google Cloud Console Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing project
3. Navigate to "APIs & Services" > "Credentials"
4. Click "Create Credentials" > "OAuth 2.0 Client ID"
5. Configure OAuth consent screen (if not already done)
6. Application type: "Web application"
7. Authorized redirect URIs: `http://localhost:8888/callback` (adjust port if needed)
8. Copy Client ID and Client Secret
9. Enable required APIs (e.g., Google Sheets API)

### Microsoft Azure AD Setup

1. Go to [Azure Portal](https://portal.azure.com/)
2. Navigate to "Azure Active Directory" > "App registrations"
3. Click "New registration"
4. Name: "NiFi Authentication Broker"
5. Supported account types: Select appropriate option
6. Redirect URI: Web - `http://localhost:8888/callback`
7. Register application
8. Copy Application (client) ID and Directory (tenant) ID
9. Navigate to "Certificates & secrets"
10. Create new client secret, copy value immediately
11. Navigate to "API permissions"
12. Add required permissions (e.g., Files.ReadWrite for OneDrive)

## Conclusion

This specification provides a complete OAuth2 authentication workflow that:
- Securely manages authentication for Google and Microsoft
- Provides a user-friendly web interface for login/logout
- Automatically handles token refresh
- Integrates seamlessly with NiFi processors
- Follows OAuth2 best practices (PKCE, state validation, secure storage)

The workflow is complete and ready for implementation. All steps from initial authentication through token usage and logout are covered with appropriate error handling and security measures.
