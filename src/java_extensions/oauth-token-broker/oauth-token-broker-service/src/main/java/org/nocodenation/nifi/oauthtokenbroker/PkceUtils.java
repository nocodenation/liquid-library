package org.nocodenation.nifi.oauthtokenbroker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility class for PKCE (Proof Key for Code Exchange) operations.
 * <p>
 * This class provides methods for generating PKCE code verifiers and challenges
 * according to RFC 7636. PKCE is an extension to the OAuth 2.0 Authorization Code
 * flow to prevent authorization code interception attacks.
 */
public class PkceUtils {
    
    /**
     * Class representing PKCE values (code verifier and code challenge).
     * <p>
     * These values must remain consistent throughout the entire OAuth 2.0 authorization flow:
     * <ol>
     *   <li>The code challenge is sent with the initial authorization request</li>
     *   <li>The code verifier is sent when exchanging the authorization code for tokens</li>
     * </ol>
     * <p>
     * According to RFC 7636, the code verifier must be a random string between 43-128 characters
     * containing only the characters: A-Z, a-z, 0-9, "-", ".", "_", "~".
     */
    public static class PkceValues {
        private final String codeVerifier;
        private final String codeChallenge;
        
        /**
         * Creates a new PkceValues instance with the specified code verifier and challenge.
         *
         * @param codeVerifier The PKCE code verifier (random string 43-128 chars)
         * @param codeChallenge The PKCE code challenge (BASE64URL-encoded SHA-256 hash of the code verifier)
         */
        public PkceValues(String codeVerifier, String codeChallenge) {
            this.codeVerifier = codeVerifier;
            this.codeChallenge = codeChallenge;
        }
        
        /**
         * Gets the PKCE code verifier.
         *
         * @return The code verifier string
         */
        public String getCodeVerifier() {
            return codeVerifier;
        }
        
        /**
         * Gets the PKCE code challenge.
         *
         * @return The code challenge string (BASE64URL-encoded SHA-256 hash of the code verifier)
         */
        public String getCodeChallenge() {
            return codeChallenge;
        }
    }
    
    /**
     * Generates PKCE values (code verifier and code challenge).
     * 
     * @return A PkceValues object containing the generated code verifier and challenge
     * @throws NoSuchAlgorithmException if SHA-256 algorithm is not available
     */
    public static PkceValues generatePkceValues() throws NoSuchAlgorithmException {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        return new PkceValues(codeVerifier, codeChallenge);
    }

    /**
     * Generates a code verifier according to RFC 7636.
     * <p>
     * The code verifier is a high-entropy cryptographic random string using only
     * the unreserved characters [A-Z], [a-z], [0-9], "-", ".", "_", "~"
     * with a minimum length of 43 characters and a maximum length of 128 characters.
     * <p>
     * This implementation generates a 43-character code verifier for simplicity.
     * 
     * @return a random string of 43 characters containing only allowed characters
     */
    public static String generateCodeVerifier() {
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder verifierBuilder = new StringBuilder();
        String allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        
        for (int i = 0; i < 43; i++) {
            int randomIndex = secureRandom.nextInt(allowedChars.length());
            verifierBuilder.append(allowedChars.charAt(randomIndex));
        }
        
        return verifierBuilder.toString();
    }
    
    /**
     * Generates a code challenge from a code verifier according to RFC 7636.
     * <p>
     * The code challenge is the BASE64URL-encoded SHA-256 hash of the ASCII
     * representation of the code verifier string.
     * <p>
     * This transformation ensures that the code verifier cannot be derived from
     * the code challenge, which is sent in the authorization request.
     * 
     * @param codeVerifier the code verifier string
     * @return the BASE64URL-encoded SHA-256 hash of the code verifier
     * @throws NoSuchAlgorithmException if SHA-256 algorithm is not available
     */
    public static String generateCodeChallenge(String codeVerifier) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] challengeBytes = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
    }
}
