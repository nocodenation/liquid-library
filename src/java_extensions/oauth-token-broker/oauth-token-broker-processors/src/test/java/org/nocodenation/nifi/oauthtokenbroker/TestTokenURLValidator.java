package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.ControllerServiceLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.nocodenation.nifi.oauthtokenbroker.OAuth2AccessTokenProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TestTokenURLValidator {

    private TokenURLValidator validator;

    @Mock
    private ValidationContext context;

    @Mock
    private ControllerServiceLookup serviceLookup;

    @Mock
    private OAuth2AccessTokenProvider tokenProvider;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new TokenURLValidator();
        when(context.getControllerServiceLookup()).thenReturn(serviceLookup);
    }

    @Test
    public void testValidateWithExpressionLanguage() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = "${service.id}";
        // No need to mock isExpressionLanguagePresent as we're directly checking for "${" in the string

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertTrue(result.isValid());
        assertEquals("Contains Expression Language", result.getExplanation());
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent since we're not using it anymore
        // Verify we don't try to look up the service when expression language is present
        verify(serviceLookup, never()).getControllerService(anyString());
    }

    @Test
    public void testValidateWithNullInput() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = null;
        // No need to mock isExpressionLanguagePresent

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertTrue(result.isValid());
        // No explanation text in the simplified version
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent
        verify(serviceLookup, never()).getControllerService(anyString());
    }

    @Test
    public void testValidateWithEmptyInput() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = "";
        // No need to mock isExpressionLanguagePresent

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertTrue(result.isValid());
        // No explanation text in the simplified version
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent
        verify(serviceLookup, never()).getControllerService(anyString());
    }

    @Test
    public void testValidateWithNonOAuth2AccessTokenProviderService() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = "service-id";
        ControllerService nonOAuth2Service = mock(ControllerService.class);
        
        // No need to mock isExpressionLanguagePresent
        when(serviceLookup.getControllerService(input)).thenReturn(nonOAuth2Service);

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertTrue(result.isValid());
        // No explanation text in the simplified version
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent
        verify(serviceLookup).getControllerService(input);
    }

    @Test
    public void testValidateWithAuthorizedOAuth2AccessTokenProvider() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = "service-id";
        
        // No need to mock isExpressionLanguagePresent
        when(serviceLookup.getControllerService(input)).thenReturn(tokenProvider);
        when(tokenProvider.isAuthorized()).thenReturn(true);

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertTrue(result.isValid());
        // No explanation text in the simplified version
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent
        verify(serviceLookup).getControllerService(input);
        verify(tokenProvider).isAuthorized();
        // Verify we don't try to get the authorization URL for an authorized provider
        verify(tokenProvider, never()).getAuthorizationUrl();
    }

    @Test
    public void testValidateWithUnauthorizedOAuth2AccessTokenProvider() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = "service-id";
        String authUrl = "https://example.com/auth";
        
        // No need to mock isExpressionLanguagePresent
        when(serviceLookup.getControllerService(input)).thenReturn(tokenProvider);
        when(tokenProvider.isAuthorized()).thenReturn(false);
        when(tokenProvider.getAuthorizationUrl()).thenReturn(authUrl);

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertFalse(result.isValid());
        assertEquals("OAuth 2.0 provider is not authorized. Authorization URL: " + authUrl, result.getExplanation());
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent
        verify(serviceLookup).getControllerService(input);
        verify(tokenProvider).isAuthorized();
        verify(tokenProvider).getAuthorizationUrl();
    }

    @Test
    public void testValidateWithServiceLookupException() {
        // Setup
        String subject = "OAuth 2.0 Token Provider";
        String input = "service-id";
        String errorMessage = "Service not available";
        RuntimeException exception = new RuntimeException(errorMessage);
        
        // No need to mock isExpressionLanguagePresent
        when(serviceLookup.getControllerService(input)).thenThrow(exception);

        // Execute
        ValidationResult result = validator.validate(subject, input, context);

        // Verify
        assertFalse(result.isValid());
        assertEquals("Error retrieving controller service: " + errorMessage, result.getExplanation());
        assertEquals(subject, result.getSubject());
        assertEquals(input, result.getInput());
        // Don't verify isExpressionLanguagePresent
        verify(serviceLookup).getControllerService(input);
    }
}
