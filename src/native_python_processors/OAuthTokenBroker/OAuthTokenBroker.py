"""
 * Copyright 2025 Dilcher GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
"""

"""
# Python OAuth2 Access Token Processor Example
# ============================================

## Overview
This file demonstrates how to create a Python processor that integrates with the OAuth2AccessTokenProvider
service to retrieve and use OAuth 2.0 access tokens in NiFi dataflows. This example serves as a reference
for developers who want to create their own processors that leverage OAuth 2.0 authentication.

## Key Concepts
1. **Controller Service Integration**: How to reference and use the OAuth2AccessTokenProvider service
2. **OAuth 2.0 Flow Handling**: Managing the complete OAuth flow including authorization, token acquisition, and refresh
3. **PKCE Implementation**: Working with the built-in PKCE (Proof Key for Code Exchange) security enhancement
4. **FlowFile Routing**: Properly routing FlowFiles based on OAuth status
5. **Error Handling**: Gracefully handling OAuth-related errors

## Implementation Steps
To create your own processor that uses the OAuth2AccessTokenProvider service:

1. Import the necessary NiFi Python API classes
2. Define your processor class extending FlowFileTransform
3. Set up relationships for success, failure, and authorization paths
4. Configure properties to reference the OAuth2AccessTokenProvider service
5. Implement validation logic to check OAuth authorization status
6. Create the transform method to handle the OAuth token acquisition and FlowFile processing

## Security Considerations
- The OAuth2AccessTokenProvider service implements PKCE by default for enhanced security
- Tokens are stored securely by the service and automatically refreshed when expired
- Authorization URLs should be handled carefully to prevent unauthorized access

## Testing Your Implementation
- Initial runs will route to the 'authorize' relationship until authorization is completed
- After authorization, tokens will be automatically acquired and refreshed
- Use the token in downstream processors via Expression Language (e.g., ${oauth2.access_token})

For more details, see the implementation below and refer to the OAuth2AccessTokenProvider
Java service documentation.
"""

from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, ValidationResult
from nifiapi.relationship import Relationship
import logging

# Set up logger
logger = logging.getLogger(__name__)


class OAuthTokenBroker(FlowFileTransform):
    """
    The OAuthTokenBroker processor retrieves OAuth 2.0 access tokens from a configured
    OAuth 2.0 Token Provider service and adds them as attributes to FlowFiles.

    This processor acts as a bridge between NiFi dataflows and OAuth 2.0 protected services
    by handling the complete OAuth 2.0 workflow, including detecting when authorization is needed,
    routing FlowFiles appropriately, and enriching FlowFiles with valid access tokens.

    Key features:
    - Automatic token acquisition from configured OAuth 2.0 providers
    - Automatic token refresh when tokens expire
    - Support for authorization URL generation for initial authorization
    - Clear routing paths for success, failure, and authorization required scenarios
    - Integration with PKCE (Proof Key for Code Exchange) for enhanced security

    Usage pattern:
    1. Configure the processor with an OAuth2AccessTokenProvider service
    2. Initially, FlowFiles will be routed to 'authorize' relationship
    3. Complete the authorization process by visiting the authorization URL
    4. Once authorized, subsequent FlowFiles will be enriched with tokens and routed to 'success'
    5. Use the token in downstream processors (e.g., InvokeHTTP) via Expression Language
    """

    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.7.0'
        description = "Retrieves OAuth 2.0 access tokens from a configured OAuth 2.0 Token Provider service and adds them as attributes to FlowFiles."
        tags = ["oauth", "oauth2", "authentication", "authorization", "token", "security", "http", "web", "api", "nocodenation"]

    # Define relationships
    REL_SUCCESS = Relationship(
        name="success",
        description="FlowFiles that have been successfully enriched with the OAuth 2.0 access token."
    )

    REL_FAILURE = Relationship(
        name="failure",
        description="FlowFiles that could not be enriched with the OAuth 2.0 access token due to errors."
    )

    REL_AUTHORIZE = Relationship(
        name="authorize",
        description="FlowFiles that require OAuth 2.0 authorization before token acquisition."
    )

    # Define properties
    OAUTH2_TOKEN_PROVIDER = PropertyDescriptor(
        name="OAuth 2.0 Token Provider",
        description="The OAuth 2.0 Token Provider service that will be used to retrieve the access token. "
                    "This service manages the OAuth 2.0 authorization flow, token acquisition, and token refresh. "
                    "The service must be properly configured and authorized before tokens can be successfully retrieved. "
                    "If the service is not yet authorized, FlowFiles will be routed to the 'authorize' relationship.",
        required=True,
        controller_service_definition="org.nocodenation.nifi.oauthtokentbroker.OAuth2AccessTokenProvider"
    )

    CUSTOM_SCOPES = PropertyDescriptor(
        name="Custom Scopes",
        description="The scope to be used when requesting the access token. "
                    "This property is optional and can be used to request a specific scope. "
                    "If not specified, the default scope will be used.",
        required=False
    )

    TOKEN_ATTRIBUTE_NAME = PropertyDescriptor(
        name="Token Attribute Name",
        description="The name of the attribute that will contain the OAuth 2.0 access token. "
                    "This attribute will be added to each FlowFile that is successfully processed. "
                    "The attribute can then be referenced in downstream processors using Expression Language, "
                    "for example in an InvokeHTTP processor's 'Authorization' header as: "
                    "'Bearer ${oauth2.access_token}'.",
        required=True,
        default_value="oauth2.access_token"
    )

    OUTPUT_AUTH_URL = PropertyDescriptor(
        name="Output Authorization URL",
        description="Whether to output the authorization URL as an attribute in the FlowFile when authorization is required. "
                    "When set to 'Yes', FlowFiles routed to the 'authorize' relationship will include an attribute "
                    "named 'oauth2.authorization.url' containing the URL that a user must visit to authorize the application. "
                    "This can be useful for automated notification workflows or for displaying the URL in a user interface.",
        required=True,
        allowable_values=["Yes", "No"],
        default_value="No"
    )

    def __init__(self, **kwargs):
        """
        Initialize the processor with its properties and relationships.

        This method sets up the processor's configuration by defining:
        - Property descriptors that configure the processor's behavior
        - Relationships that determine how FlowFiles are routed

        For custom processors:
        - Always include all necessary property descriptors
        - Define all possible routing relationships
        - Consider adding any initialization logic needed for your processor
        """
        self.descriptors = [self.OAUTH2_TOKEN_PROVIDER, self.CUSTOM_SCOPES, self.TOKEN_ATTRIBUTE_NAME, self.OUTPUT_AUTH_URL]
        self.relationships = [self.REL_SUCCESS, self.REL_FAILURE, self.REL_AUTHORIZE]

    def getPropertyDescriptors(self):
        """
        Returns the list of property descriptors supported by this processor.

        This method is required by the NiFi Python API and must return all property
        descriptors that the processor supports. These properties will appear in the
        processor's configuration UI in NiFi.

        Returns:
            list: A list of PropertyDescriptor objects
        """
        return self.descriptors

    def getRelationships(self):
        """
        Returns the set of relationships supported by this processor.

        This method is required by the NiFi Python API and must return all relationships
        that the processor supports. These relationships define how FlowFiles are routed
        after processing.

        Returns:
            list: A list of Relationship objects
        """
        return self.relationships

    def customValidate(self, context):
        """
        Performs custom validation of the processor configuration.

        This method validates the OAuth 2.0 Token Provider service using a custom validator.
        Validation is skipped if OUTPUT_AUTH_URL is enabled.

        For OAuth integration:
        - Check if the OAuth service is properly configured
        - Verify if the service is authorized
        - Provide helpful validation messages with authorization URLs when needed

        Args:
            context: The validation context

        Returns:
            A collection of validation results
        """
        results = []

        try:
            # Skip validation if OUTPUT_AUTH_URL is enabled
            output_auth_url = context.getProperty(self.OUTPUT_AUTH_URL).getValue() == "Yes"
            if output_auth_url:
                return results

            # Validate the OAuth 2.0 Token Provider
            token_provider_value = context.getProperty(self.OAUTH2_TOKEN_PROVIDER).getValue()
            token_provider = context.getProperty(self.OAUTH2_TOKEN_PROVIDER).asControllerService()
            custom_scopes = context.getProperty(self.CUSTOM_SCOPES).getValue()
            has_custom_scopes = custom_scopes is not None and len(custom_scopes) > 0

            if token_provider:
                is_authorized = False
                if has_custom_scopes:
                    is_authorized = token_provider.isAuthorized(self.identifier, custom_scopes)
                else:
                    is_authorized = token_provider.isAuthorized()

                if not is_authorized:
                    auth_url = None
                    if has_custom_scopes:
                        auth_url = token_provider.getAuthorizationUrl(self.identifier, custom_scopes)
                    else:
                        auth_url = token_provider.getAuthorizationUrl()

                    results.append(ValidationResult(
                        subject=processor_id,
                        explanation=f"OAuth 2.0 provider is not authorized. Authorization URL: {auth_url}",
                        valid=False,
                        input=token_provider_value
                    ))

        except Exception as e:
            logger.error(f"Error during validation: {str(e)}", exc_info=True)

        return results

    def transform(self, context, flowFile):
        """
        Processes incoming FlowFiles by enriching them with OAuth 2.0 access tokens.

        This method demonstrates the core OAuth 2.0 integration pattern:
        1. Checks if the OAuth 2.0 provider is authorized
        2. If not authorized, routes the FlowFile to the appropriate relationship based on settings
        3. If authorized, retrieves the access token and adds it as an attribute to the FlowFile
        4. Routes the FlowFile to success or failure based on the outcome

        Implementation notes:
        - The OAuth2AccessTokenProvider handles token caching and refresh automatically
        - PKCE is always enabled for enhanced security (as of recent updates)
        - Error handling is important to provide meaningful feedback

        Args:
            context: The process context
            flowFile: The FlowFile to process

        Returns:
            FlowFileTransformResult: The result of the transformation with the appropriate relationship
        """
        attributes = {}

        try:
            # Get the OAuth 2.0 Token Provider service
            token_provider = context.getProperty(self.OAUTH2_TOKEN_PROVIDER).asControllerService()
            token_attribute_name = context.getProperty(self.TOKEN_ATTRIBUTE_NAME).getValue()
            output_auth_url = context.getProperty(self.OUTPUT_AUTH_URL).getValue()
            custom_scopes = context.getProperty(self.CUSTOM_SCOPES).getValue()
            has_custom_scopes = custom_scopes is not None and len(custom_scopes) > 0
            processor_id = self.getIdentifier()

            # Check if the OAuth 2.0 provider is authorized
            is_authorized = False
            if has_custom_scopes:
                is_authorized = token_provider.isAuthorized(self.identifier, custom_scopes)
            else:
                is_authorized = token_provider.isAuthorized()

            if not is_authorized:
                logger.debug(f"OAuth 2.0 provider not authorized for FlowFile {flowFile.getId()}")

                authorization_url = None
                if has_custom_scopes:
                    authorization_url = token_provider.getAuthorizationUrl(self.identifier, custom_scopes)
                else:
                    authorization_url = token_provider.getAuthorizationUrl()

                # Always add the authorization URL attribute
                attributes["oauth2.authorize_url"] = authorization_url

                # Route to appropriate relationship based on OUTPUT_AUTH_URL setting
                if output_auth_url == "Yes":
                    return FlowFileTransformResult(relationship=self.REL_AUTHORIZE.name, attributes=attributes)
                else:
                    attributes["oauth2.error.message"] = "OAuth 2.0 provider not authorized"
                    return FlowFileTransformResult(relationship=self.REL_FAILURE.name, attributes=attributes)

            # Get the access token
            access_token = None
            if has_custom_scopes:
                access_token = token_provider.getAccessToken(self.identifier)
            else:
                access_token = token_provider.getAccessToken()

            # Validate the access token
            if access_token is None or access_token.getAccessToken() is None or access_token.getAccessToken() == "":
                error_message = "Failed to retrieve a valid access token"
                attributes["oauth2.error.message"] = error_message
                logger.error(f"Failed to retrieve a valid access token for FlowFile {flowFile.getId()}")
                return FlowFileTransformResult(relationship=self.REL_FAILURE.name, attributes=attributes)

            # Add the access token as an attribute
            attributes[token_attribute_name] = access_token.getAccessToken()

            # Return success
            return FlowFileTransformResult(relationship=self.REL_SUCCESS.name, attributes=attributes)

        except Exception as e:
            error_message = f"Unexpected error while processing OAuth 2.0 token: {str(e)}"
            attributes["oauth2.error.message"] = error_message
            logger.error(f"Unexpected error for FlowFile {flowFile.getId()}: {str(e)}", exc_info=True)
            return FlowFileTransformResult(relationship=self.REL_FAILURE.name, attributes=attributes)