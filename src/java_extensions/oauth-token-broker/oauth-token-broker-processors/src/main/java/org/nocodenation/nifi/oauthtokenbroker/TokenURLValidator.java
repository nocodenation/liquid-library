package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.nocodenation.nifi.oauthtokenbroker.OAuth2AccessTokenProvider;

/**
 * Validator for OAuth 2.0 Token Provider service references.
 * <p>
 * This validator performs several checks on the OAuth 2.0 Token Provider service:
 * <ol>
 *   <li>Verifies that the referenced service exists and is enabled</li>
 *   <li>Confirms that the service implements the OAuth2AccessTokenProvider</li>
 *   <li>Optionally checks if the service is already authorized</li>
 * </ol>
 * <p>
 * The validator is designed to be used with processor properties that reference
 * an OAuth 2.0 Token Provider service. It provides helpful validation messages
 * to guide users in properly configuring and authorizing the OAuth 2.0 service.
 * <p>
 * If the input contains Expression Language (e.g., ${...}), validation is skipped
 * since the actual service reference will be determined at runtime.
 * <p>
 * This validator works with the OAuth Token Broker implementation which uses PKCE
 * (Proof Key for Code Exchange) by default for all OAuth flows according to RFC 7636
 * for enhanced security against authorization code interception attacks.
 * 
 * @see org.nocodenation.nifi.oauthtokenbroker.OAuth2AccessTokenProvider
 */
public class TokenURLValidator implements Validator {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenURLValidator.class);
        

    /**
     * Validates an OAuth 2.0 Token Provider service reference.
     * <p>
     * This method performs the following validation steps:
     * <ol>
     *   <li>Checks if the input contains Expression Language - if so, skips validation</li>
     *   <li>Attempts to retrieve the controller service using the provided input</li>
     *   <li>Verifies that the service implements OAuth2AccessTokenProvider</li>
     *   <li>If the service is found and properly typed, checks if it's authorized</li>
     *   <li>If not authorized, includes the authorization URL in the validation result</li>
     * </ol>
     * <p>
     * The validation result includes detailed explanations to help users understand
     * and resolve any issues with their OAuth 2.0 Token Provider configuration.
     *
     * @param subject The name of the property being validated
     * @param input The value of the property being validated (service identifier)
     * @param context The validation context providing access to controller services
     * @return A ValidationResult indicating whether the input is valid and providing
     *         an explanation if it's not
     */
    @Override
    public ValidationResult validate(String subject, String input, ValidationContext context) {
        // Skip validation if the input contains expression language
        if (input != null && input.contains("${")) {
            return new ValidationResult.Builder()
                    .subject(subject)
                    .input(input)
                    .valid(true)
                    .explanation("Contains Expression Language")
                    .build();
        }

        // Try to get the controller service
        Object service = null;
        OAuth2AccessTokenProvider tokenProvider = null;
        try {
            if (input != null && !input.trim().isEmpty()) {
                service = context.getControllerServiceLookup().getControllerService(input);
                if (service instanceof OAuth2AccessTokenProvider) {
                    tokenProvider = (OAuth2AccessTokenProvider) service;
                } else if (service != null) {
                    LOGGER.debug("Service with ID {} is not an OAuth2AccessTokenProvider (type: {})", 
                            input, service.getClass().getName());
                }
            }
        } catch (Exception e) {
            // Log at debug level since validation errors are already displayed in the UI
            LOGGER.debug("Error retrieving controller service: {}", e.getMessage());
            return new ValidationResult.Builder()
                    .subject(subject)
                    .input(input)
                    .valid(false)
                    .explanation("Error retrieving controller service: " + e.getMessage())
                    .build();
        }

        // Check if the provider is authorized
        if (tokenProvider != null && !tokenProvider.isAuthorized()) {
            String authUrl = tokenProvider.getAuthorizationUrl();
            // No need to log this as it will be shown in the UI via validation result
            return new ValidationResult.Builder()
                    .subject(subject)
                    .input(input)
                    .valid(false)
                    .explanation("OAuth 2.0 provider is not authorized. Authorization URL: " + authUrl)
                    .build();
        }

        return new ValidationResult.Builder()
                .subject(subject)
                .input(input)
                .valid(true)
                .build();
    }
}
