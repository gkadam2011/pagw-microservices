package com.anthem.pagw.core.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagwIdGeneratorTest {

    @Test
    void testGenerate() {
        String id = PagwIdGenerator.generate();
        
        assertNotNull(id);
        assertTrue(id.startsWith("PAGW-"));
        assertTrue(PagwIdGenerator.isValid(id));
    }

    @Test
    void testGenerateWithPrefix() {
        String id = PagwIdGenerator.generate("TEST");
        
        assertNotNull(id);
        assertTrue(id.startsWith("TEST-"));
    }

    @Test
    void testUniqueness() {
        String id1 = PagwIdGenerator.generate();
        String id2 = PagwIdGenerator.generate();
        
        assertNotEquals(id1, id2);
    }

    @Test
    void testIsValid() {
        assertTrue(PagwIdGenerator.isValid("PAGW-20251219-00001-ABCD1234"));
        assertFalse(PagwIdGenerator.isValid(null));
        assertFalse(PagwIdGenerator.isValid(""));
        assertFalse(PagwIdGenerator.isValid("invalid-id"));
    }

    @Test
    void testExtractDate() {
        String date = PagwIdGenerator.extractDate("PAGW-20251219-00001-ABCD1234");
        assertEquals("20251219", date);
    }
}
