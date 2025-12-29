package org.nocodenation.nifi.oauthtokenbroker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Test class for AccessToken
 */
public class TestAccessToken {

    private AccessToken token;
    private static final String TEST_ACCESS_TOKEN = "test-access-token";
    private static final String TEST_REFRESH_TOKEN = "test-refresh-token";
    private static final String TEST_TOKEN_TYPE = "Bearer";
    private static final String TEST_SCOPE = "read write";
    private static final long TEST_EXPIRES_IN = 3600;

    @BeforeEach
    public void setUp() {
        token = new AccessToken();
    }

    @Test
    public void testDefaultConstructor() {
        assertNull(token.getAccessToken());
        assertNull(token.getRefreshToken());
        assertNull(token.getTokenType());
        assertNull(token.getExpiresAt());
        assertNull(token.getScope());
    }

    @Test
    public void testAccessTokenGetterSetter() {
        token.setAccessToken(TEST_ACCESS_TOKEN);
        assertEquals(TEST_ACCESS_TOKEN, token.getAccessToken());
    }

    @Test
    public void testRefreshTokenGetterSetter() {
        token.setRefreshToken(TEST_REFRESH_TOKEN);
        assertEquals(TEST_REFRESH_TOKEN, token.getRefreshToken());
    }

    @Test
    public void testTokenTypeGetterSetter() {
        token.setTokenType(TEST_TOKEN_TYPE);
        assertEquals(TEST_TOKEN_TYPE, token.getTokenType());
    }

    @Test
    public void testScopeGetterSetter() {
        token.setScope(TEST_SCOPE);
        assertEquals(TEST_SCOPE, token.getScope());
    }

    @Test
    public void testExpiresAtGetterSetter() {
        Instant now = Instant.now();
        token.setExpiresAt(now);
        assertEquals(now, token.getExpiresAt());
    }

    @Test
    public void testSetExpiresInSeconds() {
        Instant before = Instant.now();
        token.setExpiresInSeconds(TEST_EXPIRES_IN);
        Instant after = Instant.now();
        
        // The expiration time should be approximately TEST_EXPIRES_IN seconds from now
        // minus 30 seconds as per the implementation
        Instant expectedMin = before.plusSeconds(TEST_EXPIRES_IN - 30);
        Instant expectedMax = after.plusSeconds(TEST_EXPIRES_IN - 30);
        
        assertTrue(token.getExpiresAt().isAfter(expectedMin) || token.getExpiresAt().equals(expectedMin));
        assertTrue(token.getExpiresAt().isBefore(expectedMax) || token.getExpiresAt().equals(expectedMax));
    }

    @Test
    public void testSetExpiresAtMillis() {
        long nowMillis = Instant.now().toEpochMilli();
        token.setExpiresAtMillis(nowMillis);
        assertEquals(nowMillis, token.getExpiresAt().toEpochMilli());
    }

    @Test
    public void testGetExpiresIn() {
        // Set expiration time to 60 seconds from now
        Instant expiresAt = Instant.now().plusSeconds(60);
        token.setExpiresAt(expiresAt);
        
        // The getExpiresIn should return approximately 60 seconds
        long expiresIn = token.getExpiresIn();
        assertTrue(expiresIn > 55 && expiresIn <= 60, "Expected expiresIn to be approximately 60 seconds, but was " + expiresIn);
    }

    @Test
    public void testIsExpiredOrExpiring() {
        // Token not expired
        Instant notExpired = Instant.now().plus(100, ChronoUnit.SECONDS);
        token.setExpiresAt(notExpired);
        assertFalse(token.isExpiredOrExpiring(30));
        
        // Token expired
        Instant expired = Instant.now().minus(10, ChronoUnit.SECONDS);
        token.setExpiresAt(expired);
        assertTrue(token.isExpiredOrExpiring(30));
        
        // Token expiring soon
        Instant expiringSoon = Instant.now().plus(20, ChronoUnit.SECONDS);
        token.setExpiresAt(expiringSoon);
        assertTrue(token.isExpiredOrExpiring(30));
        
        // Null expiration time should not be considered expired
        token.setExpiresAt(null);
        assertFalse(token.isExpiredOrExpiring(30));
    }

    @Test
    public void testGetAuthorizationHeaderValue() {
        token.setAccessToken(TEST_ACCESS_TOKEN);
        token.setTokenType(TEST_TOKEN_TYPE);
        
        String expected = TEST_TOKEN_TYPE + " " + TEST_ACCESS_TOKEN;
        assertEquals(expected, token.getAuthorizationHeaderValue());
    }
}
