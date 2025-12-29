package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.ValidationResult.Builder;
import org.apache.nifi.components.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validator for OAuth 2.0 scopes that ensures the required 'openid' scope is included.
 * <p>
 * This validator is used to validate OAuth 2.0 scope strings in NiFi processor and controller
 * service properties. It ensures that the 'openid' scope is included, which is required for
 * OpenID Connect (OIDC) authentication flows. The validator is case-insensitive and handles
 * multiple scopes separated by whitespace.
 * <p>
 * The validator performs the following checks:
 * <ul>
 *   <li>Ensures the scope string is not null or empty</li>
 *   <li>Parses the scope string into individual scopes (separated by whitespace)</li>
 *   <li>Checks if at least one of the scopes matches 'openid' (case-insensitive)</li>
 * </ul>
 * <p>
 * This validator is particularly important for OAuth 2.0 flows that use PKCE (Proof Key for
 * Code Exchange) according to RFC 7636, as it ensures that the proper authentication scopes
 * are requested during the authorization process.
 * <p>
 * Example valid scope strings:
 * <ul>
 *   <li>"openid"</li>
 *   <li>"openid profile email"</li>
 *   <li>"profile openid email"</li>
 *   <li>"profile email OpenID"</li>
 * </ul>
 * <p>
 * Example invalid scope strings:
 * <ul>
 *   <li>null</li>
 *   <li>""</li>
 *   <li>"profile email"</li>
 *   <li>"openid_profile"</li>
 * </ul>
 * 
 * @see org.apache.nifi.components.Validator
 */
public class OAuth2ScopeValidator implements Validator {
    /**
     * The required scope that must be included in the scope string.
     * This is set to "openid" to ensure OpenID Connect (OIDC) authentication flows.
     */
    private static final String REQUIRED_SCOPE = "openid";
    
    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2ScopeValidator.class);

    /**
     * Validates that the input scope string contains the required 'openid' scope.
     * <p>
     * This method performs the following validation steps:
     * <ol>
     *   <li>Checks if the input is null or empty (after trimming whitespace)</li>
     *   <li>Splits the input string by whitespace to get individual scopes</li>
     *   <li>Checks each scope (case-insensitive) to see if it matches 'openid'</li>
     * </ol>
     * <p>
     * The validation result includes:
     * <ul>
     *   <li>The subject (typically the property name)</li>
     *   <li>The input value being validated</li>
     *   <li>A boolean indicating whether the validation passed</li>
     *   <li>An explanation message if validation failed</li>
     * </ul>
     * 
     * @param subject The subject of the validation (typically the property name)
     * @param input The scope string to validate
     * @param context The validation context (not used in this implementation)
     * @return A ValidationResult indicating whether the validation passed and providing an explanation if it failed
     */
    @Override
    public ValidationResult validate(String subject, String input, ValidationContext context) {
        Builder builder = new Builder()
                .subject(subject)
                .input(input);

        if (input == null || input.trim().isEmpty()) {
            LOGGER.debug("Scope is required");
            return builder
                    .valid(false)
                    .explanation("Scope is required")
                    .build();
        }

        String[] scopes = input.split("\s");
        for (String scope : scopes) {
            if (REQUIRED_SCOPE.equals(scope.trim().toLowerCase())) {
                LOGGER.debug("Scope is valid");
                return builder
                        .valid(true)
                        .explanation("Scope is valid")
                        .build();
            }
        }

        LOGGER.debug("Scope must include 'openid'");
        return builder
                .valid(false)
                .explanation("Scope must include 'openid'")
                .build();
    }
}
