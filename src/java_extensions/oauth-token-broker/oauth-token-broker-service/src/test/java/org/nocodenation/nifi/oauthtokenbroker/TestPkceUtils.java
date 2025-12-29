package org.nocodenation.nifi.oauthtokenbroker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the PkceUtils utility class.
 * Tests the generation of PKCE code verifiers and code challenges according to RFC 7636.
 */
public class TestPkceUtils {

    /**
     * Tests that the generated code verifier meets the requirements of RFC 7636:
     * - Length between 43 and 128 characters
     * - Contains only allowed characters: A-Z, a-z, 0-9, "-", ".", "_", "~"
     */
    @Test
    public void testGenerateCodeVerifier() {
        String codeVerifier = PkceUtils.generateCodeVerifier();
        
        // Verify length (should be exactly 43 characters as per implementation)
        assertEquals(43, codeVerifier.length(), "Code verifier should be 43 characters long");
        
        // Verify character set
        assertTrue(codeVerifier.matches("[A-Za-z0-9\\-._~]+"), 
                "Code verifier should only contain allowed characters: A-Z, a-z, 0-9, -, ., _, ~");
    }
    
    /**
     * Tests that multiple generated code verifiers are unique.
     * This helps verify the randomness of the generation process.
     */
    @Test
    public void testCodeVerifierUniqueness() {
        Set<String> verifiers = new HashSet<>();
        
        // Generate 100 code verifiers and ensure they're all unique
        for (int i = 0; i < 100; i++) {
            String verifier = PkceUtils.generateCodeVerifier();
            assertFalse(verifiers.contains(verifier), "Generated code verifiers should be unique");
            verifiers.add(verifier);
        }
        
        assertEquals(100, verifiers.size(), "Should have generated 100 unique code verifiers");
    }
    
    /**
     * Tests that the code challenge is correctly generated from a code verifier.
     * The code challenge should be the BASE64URL-encoded SHA-256 hash of the code verifier.
     */
    @Test
    public void testGenerateCodeChallenge() throws NoSuchAlgorithmException {
        // Use a fixed code verifier for deterministic testing
        String codeVerifier = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ";
        
        // Generate the code challenge
        String codeChallenge = PkceUtils.generateCodeChallenge(codeVerifier);
        
        // Manually compute the expected code challenge
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] challengeBytes = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
        
        // Verify the code challenge matches the expected value
        assertEquals(expectedChallenge, codeChallenge, "Code challenge should match the expected value");
    }
    
    /**
     * Tests that the generatePkceValues method correctly generates both a code verifier
     * and its corresponding code challenge.
     */
    @Test
    public void testGeneratePkceValues() throws NoSuchAlgorithmException {
        // Generate PKCE values
        PkceUtils.PkceValues pkceValues = PkceUtils.generatePkceValues();
        
        // Verify the code verifier
        String codeVerifier = pkceValues.getCodeVerifier();
        assertNotNull(codeVerifier, "Code verifier should not be null");
        assertEquals(43, codeVerifier.length(), "Code verifier should be 43 characters long");
        assertTrue(codeVerifier.matches("[A-Za-z0-9\\-._~]+"), 
                "Code verifier should only contain allowed characters");
        
        // Verify the code challenge
        String codeChallenge = pkceValues.getCodeChallenge();
        assertNotNull(codeChallenge, "Code challenge should not be null");
        
        // Verify the code challenge is correctly derived from the code verifier
        String expectedChallenge = PkceUtils.generateCodeChallenge(codeVerifier);
        assertEquals(expectedChallenge, codeChallenge, 
                "Code challenge should match the expected value derived from the code verifier");
    }
    
    /**
     * Tests that the PkceValues class correctly stores and retrieves the code verifier and challenge.
     */
    @Test
    public void testPkceValuesClass() {
        String verifier = "testVerifier123456789012345678901234567890123";
        String challenge = "testChallenge123456789012345678901234567890";
        
        PkceUtils.PkceValues values = new PkceUtils.PkceValues(verifier, challenge);
        
        assertEquals(verifier, values.getCodeVerifier(), "Code verifier should match the value provided to constructor");
        assertEquals(challenge, values.getCodeChallenge(), "Code challenge should match the value provided to constructor");
    }
    
    /**
     * Tests that multiple calls to generatePkceValues produce different values.
     * This helps ensure that each OAuth flow gets unique PKCE values.
     */
    @RepeatedTest(5)
    public void testGeneratePkceValuesUniqueness() throws NoSuchAlgorithmException {
        PkceUtils.PkceValues values1 = PkceUtils.generatePkceValues();
        PkceUtils.PkceValues values2 = PkceUtils.generatePkceValues();
        
        // Verify that two consecutive calls generate different values
        assertNotEquals(values1.getCodeVerifier(), values2.getCodeVerifier(), 
                "Different calls to generatePkceValues should produce different code verifiers");
        assertNotEquals(values1.getCodeChallenge(), values2.getCodeChallenge(), 
                "Different calls to generatePkceValues should produce different code challenges");
    }
    
    /**
     * Tests the behavior when a null code verifier is provided to generateCodeChallenge.
     * This should throw a NullPointerException.
     */
    @Test
    public void testGenerateCodeChallengeWithNullVerifier() {
        assertThrows(NullPointerException.class, () -> {
            PkceUtils.generateCodeChallenge(null);
        }, "Passing null to generateCodeChallenge should throw NullPointerException");
    }
}
