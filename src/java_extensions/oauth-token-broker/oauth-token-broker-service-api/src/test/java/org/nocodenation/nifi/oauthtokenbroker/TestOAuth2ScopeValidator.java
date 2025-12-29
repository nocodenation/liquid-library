package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.components.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for OAuth2ScopeValidator
 */
public class TestOAuth2ScopeValidator {

    private OAuth2ScopeValidator validator;
    private static final String SUBJECT = "OAuth2 Scope";

    @BeforeEach
    public void setUp() {
        validator = new OAuth2ScopeValidator();
    }

    @Test
    public void testValidateWithNullScope() {
        ValidationResult result = validator.validate(SUBJECT, null, null);
        
        assertFalse(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals(null, result.getInput());
        assertEquals("Scope is required", result.getExplanation());
    }
    
    @Test
    public void testValidateWithEmptyScope() {
        ValidationResult result = validator.validate(SUBJECT, "", null);
        
        assertFalse(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("", result.getInput());
        assertEquals("Scope is required", result.getExplanation());
    }
    
    @Test
    public void testValidateWithWhitespaceScope() {
        ValidationResult result = validator.validate(SUBJECT, "   ", null);
        
        assertFalse(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("   ", result.getInput());
        assertEquals("Scope is required", result.getExplanation());
    }
    
    @Test
    public void testValidateWithOpenIdScope() {
        ValidationResult result = validator.validate(SUBJECT, "openid", null);
        
        assertTrue(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("openid", result.getInput());
        assertEquals("Scope is valid", result.getExplanation());
    }
    
    @Test
    public void testValidateWithMixedCaseOpenIdScope() {
        ValidationResult result = validator.validate(SUBJECT, "OpenID", null);
        
        assertTrue(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("OpenID", result.getInput());
        assertEquals("Scope is valid", result.getExplanation());
    }
    
    @Test
    public void testValidateWithMultipleScopesIncludingOpenId() {
        ValidationResult result = validator.validate(SUBJECT, "profile email openid", null);
        
        assertTrue(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("profile email openid", result.getInput());
        assertEquals("Scope is valid", result.getExplanation());
    }
    
    @Test
    public void testValidateWithMultipleScopesIncludingMixedCaseOpenId() {
        ValidationResult result = validator.validate(SUBJECT, "profile email OpenID", null);
        
        assertTrue(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("profile email OpenID", result.getInput());
        assertEquals("Scope is valid", result.getExplanation());
    }
    
    @Test
    public void testValidateWithOpenIdScopeInTheMiddle() {
        ValidationResult result = validator.validate(SUBJECT, "profile openid email", null);
        
        assertTrue(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("profile openid email", result.getInput());
        assertEquals("Scope is valid", result.getExplanation());
    }
    
    @Test
    public void testValidateWithoutOpenIdScope() {
        ValidationResult result = validator.validate(SUBJECT, "profile email", null);
        
        assertFalse(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("profile email", result.getInput());
        assertEquals("Scope must include 'openid'", result.getExplanation());
    }
    
    @Test
    public void testValidateWithSimilarButNotExactOpenIdScope() {
        ValidationResult result = validator.validate(SUBJECT, "openid_profile", null);
        
        assertFalse(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("openid_profile", result.getInput());
        assertEquals("Scope must include 'openid'", result.getExplanation());
    }
    
    @Test
    public void testValidateWithExtraWhitespace() {
        ValidationResult result = validator.validate(SUBJECT, "  profile   openid  email  ", null);
        
        assertTrue(result.isValid());
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("  profile   openid  email  ", result.getInput());
        assertEquals("Scope is valid", result.getExplanation());
    }
}
