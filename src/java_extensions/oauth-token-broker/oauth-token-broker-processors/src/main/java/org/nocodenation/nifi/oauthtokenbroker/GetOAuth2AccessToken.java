package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.nocodenation.nifi.oauthtokenbroker.AccessToken;
import org.nocodenation.nifi.oauthtokenbroker.AccessTokenAcquisitionException;
import org.nocodenation.nifi.oauthtokenbroker.OAuth2AccessTokenProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>The GetOAuth2AccessToken processor retrieves OAuth 2.0 access tokens from a configured
 * OAuth 2.0 Token Provider service and adds them as attributes to FlowFiles.</p>
 * 
 * <p>This processor acts as a bridge between NiFi dataflows and OAuth 2.0 protected services
 * by handling the complete OAuth 2.0 workflow, including detecting when authorization is needed,
 * routing FlowFiles appropriately, and enriching FlowFiles with valid access tokens.</p>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Automatic token acquisition from configured OAuth 2.0 providers</li>
 *   <li>Automatic token refresh when tokens expire</li>
 *   <li>Support for authorization URL generation for initial authorization</li>
 *   <li>Clear routing paths for success, failure, and authorization required scenarios</li>
 *   <li>Integration with PKCE (Proof Key for Code Exchange) for enhanced security</li>
 * </ul>
 * 
 * <p>Usage pattern:</p>
 * <ol>
 *   <li>Configure the processor with an OAuth2AccessTokenProvider service</li>
 *   <li>Initially, FlowFiles will be routed to 'authorize' relationship</li>
 *   <li>Complete the authorization process by visiting the authorization URL</li>
 *   <li>Once authorized, subsequent FlowFiles will be enriched with tokens and routed to 'success'</li>
 *   <li>Use the token in downstream processors (e.g., InvokeHTTP) via Expression Language</li>
 * </ol>
 * 
 * <p><strong>Processor Development Guide:</strong></p>
 * <p>This processor demonstrates several key patterns for NiFi processor development:</p>
 * <ol>
 *   <li><strong>Controller Service Integration</strong>: Shows how to use a controller service 
 *       (OAuth2AccessTokenProvider) to encapsulate complex functionality outside the processor</li>
 *   <li><strong>Multiple Relationships</strong>: Demonstrates how to define and use multiple 
 *       relationships for different processing outcomes (success, failure, authorize)</li>
 *   <li><strong>FlowFile Attribute Manipulation</strong>: Shows how to add attributes to FlowFiles 
 *       that can be used by downstream processors</li>
 *   <li><strong>Error Handling</strong>: Demonstrates proper error handling patterns with 
 *       appropriate logging and FlowFile routing</li>
 *   <li><strong>Stateless Processing</strong>: Shows how to implement a processor that doesn't 
 *       maintain internal state, delegating stateful operations to a controller service</li>
 *   <li><strong>Custom Validation</strong>: Implements custom validation logic for processor 
 *       properties</li>
 * </ol>
 * 
 * <p><strong>Relationship Termination:</strong></p>
 * <p>In NiFi, relationships can be auto-terminated in the UI. When a relationship is auto-terminated,
 * FlowFiles sent to that relationship are removed from the flow. This is useful for endpoints in a flow
 * or for error handling. When developing a processor, you should consider which relationships might
 * be appropriate for auto-termination and document them clearly.</p>
 * 
 * <p><strong>Extending This Processor:</strong></p>
 * <p>When creating your own processor based on this example, consider:</p>
 * <ul>
 *   <li>Clearly defining your processor's purpose and the problem it solves</li>
 *   <li>Identifying appropriate relationships for different processing outcomes</li>
 *   <li>Determining what properties users need to configure</li>
 *   <li>Implementing proper validation for those properties</li>
 *   <li>Handling errors gracefully with appropriate logging</li>
 *   <li>Adding clear documentation with @CapabilityDescription and property descriptions</li>
 *   <li>Using appropriate annotations to describe processor behavior</li>
 * </ul>
 */
@Tags({"oauth", "oauth2", "authentication", "authorization", "token", "security", "http", "web", "api", "nocodenation"})
@CapabilityDescription("Retrieves OAuth 2.0 access tokens from a configured OAuth 2.0 Token Provider service and " +
        "adds them as attributes to FlowFiles. This processor handles the complete OAuth 2.0 workflow, including " +
        "detecting when authorization is needed, routing FlowFiles appropriately, and enriching FlowFiles with " +
        "valid access tokens. It supports automatic token refresh when tokens expire and provides clear routing " +
        "paths for success, failure, and authorization required scenarios.")
@InputRequirement(Requirement.INPUT_REQUIRED)
@SupportsBatching
@WritesAttributes({
    @WritesAttribute(attribute = "oauth2.access_token", description = "The OAuth 2.0 access token retrieved from the provider"),
    @WritesAttribute(attribute = "oauth2.token.type", description = "The type of token, typically 'Bearer'"),
    @WritesAttribute(attribute = "oauth2.expires.in", description = "The token expiration time in seconds"),
    @WritesAttribute(attribute = "oauth2.authorization.url", description = "The authorization URL that the user needs to visit (only when authorization is required)")
})
@SeeAlso({OAuth2AccessTokenProvider.class})
public class GetOAuth2AccessToken extends AbstractProcessor {

    /**
     * Logger for this class.
     * 
     * <p>Best Practice: Always use a class-specific logger with the appropriate logging level.
     * This allows for targeted log filtering and helps with troubleshooting.</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(GetOAuth2AccessToken.class);

    /**
     * Property descriptor for the OAuth 2.0 Token Provider service.
     * This service is responsible for the actual token acquisition, refresh, and authorization URL generation.
     * 
     * <p>Development Note: When creating a processor that uses a controller service, define a property
     * descriptor that identifies the controller service interface. This allows users to select
     * an appropriate implementation of that service in the NiFi UI.</p>
     * 
     * <p>The identifiesControllerService method specifies which controller service interface
     * this property should reference.</p>
     */
    public static final PropertyDescriptor OAUTH2_TOKEN_PROVIDER = new PropertyDescriptor.Builder()
            .name("OAuth 2.0 Token Provider")
            .description("The OAuth 2.0 Token Provider service that will be used to retrieve the access token. " +
                    "This service manages the OAuth 2.0 authorization flow, token acquisition, and token refresh. " +
                    "The service must be properly configured and authorized before tokens can be successfully retrieved. " +
                    "If the service is not yet authorized, FlowFiles will be routed to the 'authorize' relationship.")
            .identifiesControllerService(OAuth2AccessTokenProvider.class)
            .required(true)
            .build();
    
    public static final PropertyDescriptor CUSTOM_SCOPES = new PropertyDescriptor.Builder()
            .name("Custom Scopes")
            .description("The scope to be used when requesting the access token. " +
                    "This property is optional and can be used to request a specific scope. " +
                    "If not specified, the default scope will be used.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Property descriptor for the attribute name that will contain the OAuth 2.0 access token.
     * 
     * <p>Development Note: For properties that affect how data is processed or where results are stored,
     * provide clear descriptions and appropriate default values. Using StandardValidators
     * ensures that user input is properly validated.</p>
     */
    public static final PropertyDescriptor TOKEN_ATTRIBUTE_NAME = new PropertyDescriptor.Builder()
            .name("Token Attribute Name")
            .description("The name of the attribute that will contain the OAuth 2.0 access token. " +
                    "This attribute will be added to each FlowFile that is successfully processed. " +
                    "The attribute can then be referenced in downstream processors using Expression Language, " +
                    "for example in an InvokeHTTP processor's 'Authorization' header as: " +
                    "'Bearer ${oauth2.access_token}'.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("oauth2.access_token")
            .build();
            
    /**
     * Property descriptor for whether to output the authorization URL as an attribute.
     * 
     * <p>Development Note: For boolean properties, use allowableValues to restrict
     * input to specific values. This creates a dropdown in the UI rather than a text field.</p>
     */
    public static final PropertyDescriptor OUTPUT_AUTH_URL = new PropertyDescriptor.Builder()
            .name("Output Authorization URL")
            .description("Whether to output the authorization URL as an attribute in the FlowFile when authorization is required. " +
                    "When set to 'Yes', FlowFiles routed to the 'authorize' relationship will include an attribute " +
                    "named 'oauth2.authorization.url' containing the URL that a user must visit to authorize the application. " +
                    "This can be useful for automated notification workflows or for displaying the URL in a user interface.")
            .required(true)
            .allowableValues("Yes", "No")
            .defaultValue("No")
            .build();
            
    /**
     * Relationship for FlowFiles that have been successfully enriched with the OAuth 2.0 access token.
     * 
     * <p>Development Note: Relationships define the possible output paths for FlowFiles processed by this processor.
     * Each relationship should have a clear purpose and be well-documented. The description should explain
     * when FlowFiles will be routed to this relationship and what attributes or content changes to expect.</p>
     * 
     * <p>In the NiFi UI, users can connect these relationships to other processors or terminate them as needed.</p>
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles that have been successfully enriched with the OAuth 2.0 access token. " +
                    "These FlowFiles will have the access token added as an attribute with the name specified " +
                    "in the 'Token Attribute Name' property. Additional token metadata such as token type and " +
                    "expiration time may also be included as attributes.")
            .build();

    /**
     * Relationship for FlowFiles that could not be enriched with the OAuth 2.0 access token due to errors.
     * 
     * <p>Development Note: Error relationships are important for proper flow management. They allow
     * users to handle error cases differently from success cases, enabling retry logic,
     * notification systems, or other error handling mechanisms.</p>
     */
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("FlowFiles that could not be enriched with the OAuth 2.0 access token due to errors. " +
                    "This can happen if the token service encounters network issues, the refresh token is invalid, " +
                    "or other OAuth-related errors occur. Error details will be logged and may be added as " +
                    "attributes to the FlowFile.")
            .build();

    /**
     * Relationship for FlowFiles that require OAuth 2.0 authorization before token acquisition.
     * 
     * <p>Development Note: Special-purpose relationships can handle specific processing states.
     * This relationship is used when the OAuth service needs authorization, allowing for
     * specialized handling of this condition in the flow.</p>
     */
    public static final Relationship REL_AUTHORIZE = new Relationship.Builder()
            .name("authorize")
            .description("FlowFiles that require OAuth 2.0 authorization before token acquisition. " +
                    "This happens when the OAuth 2.0 Token Provider service has not yet been authorized. " +
                    "If 'Output Authorization URL' is set to 'Yes', these FlowFiles will include an attribute " +
                    "named 'oauth2.authorization.url' containing the URL that a user must visit to complete " +
                    "the authorization process. After authorization is complete, subsequent FlowFiles will be " +
                    "routed to 'success'.")
            .build();

    /**
     * List of property descriptors supported by this processor.
     * This is initialized in the init method and returned by getSupportedPropertyDescriptors.
     * 
     * <p>Development Note: Storing property descriptors in an instance variable allows them
     * to be initialized once and reused, improving performance.</p>
     */
    private List<PropertyDescriptor> properties;
    
    /**
     * Set of relationships supported by this processor.
     * This is initialized in the init method and returned by getRelationships.
     * 
     * <p>Development Note: Storing relationships in an instance variable allows them
     * to be initialized once and reused, improving performance.</p>
     */
    private Set<Relationship> relationships;

    /**
     * Initializes the processor with its properties and relationships.
     * 
     * <p>Development Note: The init method is called once when the processor is created.
     * It's the appropriate place to initialize instance variables, especially those
     * that won't change during the processor's lifecycle.</p>
     * 
     * <p>This method should not perform any heavy operations or external connections,
     * as it's called during NiFi startup and configuration loading.</p>
     * 
     * @param context The processor initialization context
     */
    @Override
    protected void init(final ProcessorInitializationContext context) {
        // Initialize property descriptors
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(OAUTH2_TOKEN_PROVIDER);
        props.add(CUSTOM_SCOPES);
        props.add(TOKEN_ATTRIBUTE_NAME);
        props.add(OUTPUT_AUTH_URL);
        this.properties = Collections.unmodifiableList(props);

        // Initialize relationships
        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_FAILURE);
        rels.add(REL_AUTHORIZE); // Include REL_AUTHORIZE in all cases, but it will only be used conditionally
        this.relationships = Collections.unmodifiableSet(rels);
    }

    /**
     * Returns the set of relationships supported by this processor.
     * 
     * <p>Development Note: This method is called by the NiFi framework to determine
     * what relationships are available for this processor. The relationships returned
     * here will be available for users to connect in the NiFi UI.</p>
     * 
     * @return The set of relationships
     */
    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }
    
    /**
     * Returns the list of property descriptors supported by this processor.
     * 
     * <p>Development Note: This method is called by the NiFi framework to determine
     * what properties are available for this processor. The properties returned
     * here will be displayed in the processor's configuration dialog in the NiFi UI.</p>
     * 
     * @return The list of property descriptors
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return this.properties;
    }
    
    /**
     * Performs custom validation of the processor configuration.
     * 
     * <p>Development Note: Custom validation allows processors to implement complex
     * validation logic beyond what the standard validators provide. This is useful
     * for validating combinations of properties or checking external dependencies.</p>
     * 
     * <p>This method is called by the NiFi framework when a user configures the processor.
     * It should return a collection of ValidationResult objects that indicate any
     * validation issues. An empty collection indicates that validation passed.</p>
     * 
     * <p>This method validates the OAuth 2.0 Token Provider service using a custom validator.
     * Validation is skipped if OUTPUT_AUTH_URL is enabled.</p>
     * 
     * @param validationContext The validation context
     * @return A collection of validation results
     */
    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>();
        
        // Skip validation if OUTPUT_AUTH_URL is enabled
        final boolean outputAuthUrl = validationContext.getProperty(OUTPUT_AUTH_URL).getValue().equals("Yes");
        if (outputAuthUrl) {
            return results;
        }

        // Validate the OAuth 2.0 Token Provider
        final String OAuth2AccessTokenProviderValue = validationContext.getProperty(OAUTH2_TOKEN_PROVIDER).getValue();
        final OAuth2AccessTokenProvider tokenProvider = validationContext.getProperty(OAUTH2_TOKEN_PROVIDER)
                .asControllerService(OAuth2AccessTokenProvider.class);

        final String customScopes = validationContext.getProperty(CUSTOM_SCOPES).getValue();
        final boolean hasCustomScopes = customScopes != null && !customScopes.isEmpty();
        final String processorId = getIdentifier();

        
        
        if (tokenProvider != null) {
            boolean isAuthorized;
            if (hasCustomScopes) {
                isAuthorized = tokenProvider.isAuthorized(processorId, customScopes);
            } else {
                isAuthorized = tokenProvider.isAuthorized();
            }

            if (!isAuthorized) {

                String authUrl;
                
                if (hasCustomScopes) {
                    authUrl = tokenProvider.getAuthorizationUrl(processorId, customScopes);
                } else {
                    authUrl = tokenProvider.getAuthorizationUrl();
                }

                // No need to log this as it will be shown in the UI via validation result
                results.add(new ValidationResult.Builder()
                        .subject(processorId)
                        .input(OAuth2AccessTokenProviderValue)
                        .valid(false)
                        .explanation("OAuth 2.0 provider is not authorized. Authorization URL: " + authUrl)
                        .build());

            }
        }
        
        return results;
    }

    /**
     * Processes incoming FlowFiles by enriching them with OAuth 2.0 access tokens.
     * 
     * <p>Development Note: The onTrigger method is the heart of a processor. It's called
     * whenever the processor needs to process a FlowFile. This method should:</p>
     * <ol>
     *   <li>Get FlowFiles from the session (if input is required)</li>
     *   <li>Process those FlowFiles according to the processor's purpose</li>
     *   <li>Transfer the processed FlowFiles to appropriate relationships</li>
     *   <li>Handle errors gracefully</li>
     * </ol>
     * 
     * <p>This method should be designed to be idempotent and thread-safe, as it may
     * be called concurrently by multiple threads if the processor's concurrent tasks
     * setting is greater than 1.</p>
     * 
     * <p>This method:</p>
     * <ol>
     *   <li>Checks if the OAuth 2.0 provider is authorized</li>
     *   <li>If not authorized, routes the FlowFile to the appropriate relationship based on settings</li>
     *   <li>If authorized, retrieves the access token and adds it as an attribute to the FlowFile</li>
     *   <li>Routes the FlowFile to success or failure based on the outcome</li>
     * </ol>
     * 
     * @param context The process context
     * @param session The process session
     * @throws ProcessException If an unexpected error occurs during processing
     */
    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final OAuth2AccessTokenProvider tokenProvider = context.getProperty(OAUTH2_TOKEN_PROVIDER)
                .asControllerService(OAuth2AccessTokenProvider.class);
        final String tokenAttributeName = context.getProperty(TOKEN_ATTRIBUTE_NAME).getValue();
        final String customScopes = context.getProperty(CUSTOM_SCOPES).getValue();
        final boolean hasCustomScopes = customScopes != null && !customScopes.isEmpty();
        final String processorId = getIdentifier();

        try {
            // Check if the OAuth 2.0 provider is authorized
            boolean isAuthorized;

            if (hasCustomScopes) {
                isAuthorized = tokenProvider.isAuthorized(processorId, customScopes);
            } else {
                isAuthorized = tokenProvider.isAuthorized();
            }

            if (!isAuthorized) {
                LOGGER.debug("OAuth 2.0 provider not authorized for FlowFile {}", flowFile.getId());

                String authorizationUrl;

                if (hasCustomScopes) {
                    authorizationUrl = tokenProvider.getAuthorizationUrl(processorId, customScopes);
                } else {
                    authorizationUrl = tokenProvider.getAuthorizationUrl();
                }
                
                // Always add the authorization URL attribute
                flowFile = session.putAttribute(flowFile, "oauth2.authorize_url", authorizationUrl);
                
                // Route to appropriate relationship based on OUTPUT_AUTH_URL setting
                boolean outputAuthUrl = context.getProperty(OUTPUT_AUTH_URL).getValue().equals("Yes");
                if (outputAuthUrl) {
                    session.transfer(flowFile, REL_AUTHORIZE);
                } else {
                    session.transfer(flowFile, REL_FAILURE);
                }
                return;
            }

            // Get the access token
            AccessToken accessToken;

            if (hasCustomScopes) {
                accessToken = tokenProvider.getAccessToken(processorId);
            } else {
                accessToken = tokenProvider.getAccessToken();
            }
            
            // Validate the access token
            if (accessToken == null || accessToken.getAccessToken() == null || accessToken.getAccessToken().isEmpty()) {
                flowFile = session.putAttribute(flowFile, "oauth2.error.message", "Failed to retrieve a valid access token");
                LOGGER.error("Failed to retrieve a valid access token for FlowFile {}", flowFile.getId());
                session.transfer(flowFile, REL_FAILURE);
                return;
            }

            // Use the access token directly without including token type
            final String tokenValue = accessToken.getAccessToken();

            // Update the FlowFile with the token attribute
            flowFile = session.putAttribute(flowFile, tokenAttributeName, tokenValue);
            
            // Transfer to success relationship
            session.transfer(flowFile, REL_SUCCESS);
            
        } catch (AccessTokenAcquisitionException e) {
            String errorMessage = "Failed to acquire OAuth 2.0 access token: " + e.getMessage();
            LOGGER.error("OAuth token acquisition failed for FlowFile {}: {}", flowFile.getId(), e.getMessage());
            flowFile = session.putAttribute(flowFile, "oauth2.error.message", errorMessage);
            session.transfer(flowFile, REL_FAILURE);
        } catch (Exception e) {
            String errorMessage = "Unexpected error while processing OAuth 2.0 token: " + e.getMessage();
            LOGGER.error("Unexpected error for FlowFile {}: {}", flowFile.getId(), e.getMessage());
            flowFile = session.putAttribute(flowFile, "oauth2.error.message", errorMessage);
            session.transfer(flowFile, REL_FAILURE);
        }
    }
}
