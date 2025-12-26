package com.anthem.pagw.core.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonUtilsTest {

    @Test
    void testToJson() {
        TestDto dto = new TestDto("test", 123);
        String json = JsonUtils.toJson(dto);
        
        assertNotNull(json);
        assertTrue(json.contains("\"name\":\"test\""));
        assertTrue(json.contains("\"value\":123"));
    }

    @Test
    void testFromJson() {
        String json = "{\"name\":\"test\",\"value\":123}";
        TestDto dto = JsonUtils.fromJson(json, TestDto.class);
        
        assertEquals("test", dto.getName());
        assertEquals(123, dto.getValue());
    }

    @Test
    void testIsValidJson() {
        assertTrue(JsonUtils.isValidJson("{}"));
        assertTrue(JsonUtils.isValidJson("{\"key\":\"value\"}"));
        assertFalse(JsonUtils.isValidJson("invalid"));
        assertFalse(JsonUtils.isValidJson("{incomplete"));
    }

    static class TestDto {
        private String name;
        private int value;

        public TestDto() {}
        public TestDto(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
