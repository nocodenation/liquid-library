package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.controller.ControllerService;


/**
 * Controller service that provides OAuth 2.0 token management capabilities.
 * <p>
 * This interface defines the contract for OAuth 2.0 token providers that handle the complete
 * OAuth 2.0 authorization flow, including authorization code exchange, token refresh, and token storage.
 * Implementations should support PKCE (Proof Key for Code Exchange) for enhanced security according to RFC 7636.
 * <p>
 * The service can be used with various OAuth 2.0 providers including Google, Microsoft, and other standard
 * OAuth 2.0 implementations. Once authorized, the service manages token lifecycle including automatic
 * refreshing of expired tokens.
 * <p>
 * This service supports multiple processors sharing a single controller service instance by using
 * the processorId parameter to maintain separate token states for each processor.
 */
@Tags({"OAuth2", "Access", "Token", "Provider", "NoCodeNation"})
@CapabilityDescription("Provides an OAuth2 access token.")
public interface OAuth2AccessTokenProvider extends ControllerService {

    /**
     * Gets the current access token details, refreshing if necessary.
     * <p>
     * This method will:
     * <ol>
     *   <li>Return the current valid token if one exists and is not near expiration</li>
     *   <li>Automatically refresh the token if it exists but is near expiration</li>
     *   <li>Throw an exception if no token exists or if the refresh fails</li>
     * </ol>
     * <p>
     * The returned AccessToken contains the token value, expiration time, and other metadata
     * that can be used to make authenticated requests to OAuth 2.0 protected resources.
     * 
     * @return The current access token with its metadata
     * @throws AccessTokenAcquisitionException if there's an issue acquiring or refreshing the token,
     *         such as when the service is not yet authorized, the refresh token is invalid, or
     *         there are network connectivity issues with the token endpoint
     */
    AccessToken getAccessToken() throws AccessTokenAcquisitionException;
    
    /**
     * Gets the current access token details, refreshing if necessary.
     * <p>
     * This method will:
     * <ol>
     *   <li>Return the current valid token if one exists and is not near expiration</li>
     *   <li>Automatically refresh the token if it exists but is near expiration</li>
     *   <li>Throw an exception if no token exists or if the refresh fails</li>
     * </ol>
     * <p>
     * The returned AccessToken contains the token value, expiration time, and other metadata
     * that can be used to make authenticated requests to OAuth 2.0 protected resources.
     * 
     * @param processorId The unique identifier of the processor requesting the token, allowing
     *                    a single controller service to maintain separate token states for multiple processors
     * @return The current access token with its metadata
     * @throws AccessTokenAcquisitionException if there's an issue acquiring or refreshing the token,
     *         such as when the service is not yet authorized, the refresh token is invalid, or
     *         there are network connectivity issues with the token endpoint
     */
    AccessToken getAccessToken(String processorId) throws AccessTokenAcquisitionException;
    
    /**
     * Gets the authorization URL that the user needs to visit to authorize the application.
     * <p>
     * This URL includes all necessary parameters for the OAuth 2.0 authorization request, including:
     * <ul>
     *   <li>client_id - The OAuth 2.0 client identifier</li>
     *   <li>response_type - Set to "code" for authorization code flow</li>
     *   <li>redirect_uri - Where the authorization server will send the user after approval</li>
     *   <li>scope - The requested access scopes</li>
     *   <li>state - A security parameter to prevent CSRF attacks</li>
     *   <li>code_challenge and code_challenge_method - PKCE parameters for enhanced security</li>
     * </ul>
     * <p>
     * The user or system administrator must navigate to this URL in a web browser, authenticate with
     * the OAuth provider, and grant the requested permissions to complete the authorization flow.
     * 
     * @return The complete authorization URL for initiating the OAuth 2.0 flow
     */
    String getAuthorizationUrl();

    /**
     * Gets the authorization URL that the user needs to visit to authorize the application.
     * <p>
     * This URL includes all necessary parameters for the OAuth 2.0 authorization request, including:
     * <ul>
     *   <li>client_id - The OAuth 2.0 client identifier</li>
     *   <li>response_type - Set to "code" for authorization code flow</li>
     *   <li>redirect_uri - Where the authorization server will send the user after approval</li>
     *   <li>scope - The requested access scopes</li>
     *   <li>state - A security parameter to prevent CSRF attacks</li>
     *   <li>code_challenge and code_challenge_method - PKCE parameters for enhanced security</li>
     * </ul>
     * <p>
     * The user or system administrator must navigate to this URL in a web browser, authenticate with
     * the OAuth provider, and grant the requested permissions to complete the authorization flow.
     * 
     * @param processorId The unique identifier of the processor requesting the authorization URL,
     *                    allowing a single controller service to maintain separate authorization
     *                    flows for multiple processors
     * @return The complete authorization URL for initiating the OAuth 2.0 flow
     */
    String getAuthorizationUrl(String processorId, String scope);
    
    /**
     * Checks if the OAuth flow has been completed and tokens are available.
     * <p>
     * This method returns true only when:
     * <ol>
     *   <li>The user has completed the authorization flow by visiting the authorization URL</li>
     *   <li>The authorization code has been successfully exchanged for tokens</li>
     *   <li>The access token (and optionally refresh token) are stored and available for use</li>
     * </ol>
     * <p>
     * This method can be used to determine if the service is ready to provide tokens for
     * authenticated API requests or if the authorization flow needs to be initiated first.
     * 
     * @return true if the OAuth flow is complete and tokens are available, false otherwise
     */
    boolean isAuthorized();

    /**
     * Checks if the OAuth flow has been completed and tokens are available.
     * <p>
     * This method returns true only when:
     * <ol>
     *   <li>The user has completed the authorization flow by visiting the authorization URL</li>
     *   <li>The authorization code has been successfully exchanged for tokens</li>
     *   <li>The access token (and optionally refresh token) are stored and available for use</li>
     * </ol>
     * <p>
     * This method can be used to determine if the service is ready to provide tokens for
     * authenticated API requests or if the authorization flow needs to be initiated first.
     * 
     * @param processorId The unique identifier of the processor checking authorization status,
     *                    allowing a single controller service to maintain separate authorization
     *                    states for multiple processors
     * @return true if the OAuth flow is complete and tokens are available, false otherwise
     */
    boolean isAuthorized(String processorId, String scopes);
}
