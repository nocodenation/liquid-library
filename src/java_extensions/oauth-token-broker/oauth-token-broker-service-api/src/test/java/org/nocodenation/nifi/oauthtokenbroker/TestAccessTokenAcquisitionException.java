package org.nocodenation.nifi.oauthtokenbroker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for AccessTokenAcquisitionException
 */
public class TestAccessTokenAcquisitionException {

    private static final String TEST_MESSAGE = "Test error message";
    private static final Exception TEST_CAUSE = new RuntimeException("Test cause");

    @Test
    public void testConstructorWithMessage() {
        AccessTokenAcquisitionException exception = new AccessTokenAcquisitionException(TEST_MESSAGE);
        
        assertEquals(TEST_MESSAGE, exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    public void testConstructorWithMessageAndCause() {
        AccessTokenAcquisitionException exception = new AccessTokenAcquisitionException(TEST_MESSAGE, TEST_CAUSE);
        
        assertEquals(TEST_MESSAGE, exception.getMessage());
        assertSame(TEST_CAUSE, exception.getCause());
    }

    @Test
    public void testConstructorWithCause() {
        AccessTokenAcquisitionException exception = new AccessTokenAcquisitionException(TEST_CAUSE.getMessage());
        
        assertEquals(TEST_CAUSE.getMessage(), exception.getMessage());
        assertNull(exception.getCause());
    }
}
