package com.anthem.pagw.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagwPropertiesTest {

    @Test
    void testDefaultProperties() {
        PagwProperties props = new PagwProperties();
        
        assertEquals("pagw", props.getApplicationId());
        assertEquals("us-east-1", props.getAws().getRegion());
        assertTrue(props.getAws().getSqs().isEnabled());
        assertTrue(props.getAws().getS3().isEnabled());
        assertTrue(props.getAws().getSecrets().isEnabled());
        assertFalse(props.getAws().getDynamodb().isEnabled());
    }

    @Test
    void testDatabaseDefaults() {
        PagwProperties props = new PagwProperties();
        
        assertEquals(5432, props.getDatabase().getPort());
        assertEquals("pagw", props.getDatabase().getName());
        assertEquals("pagw", props.getDatabase().getSchema());
    }
}
