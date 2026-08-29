package com.sdncustom.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CredentialRedactorTest {

    private final CredentialRedactor redactor = new CredentialRedactor(new ObjectMapper());

    @Test
    void masksPasswordField() throws Exception {
        String result = redactor.redact("{\"broker\":\"tcp://localhost:1883\",\"username\":\"u\",\"password\":\"p@ss\"}");
        ObjectMapper mapper = new ObjectMapper();
        var map = mapper.readValue(result, java.util.Map.class);
        assertEquals("******", map.get("password"));
        assertEquals("u", map.get("username"));
        assertEquals("tcp://localhost:1883", map.get("broker"));
    }

    @Test
    void caseInsensitiveAndVariants() throws Exception {
        String result = redactor.redact("{\"MQTT_Password\":\"a\",\"accessToken\":\"b\",\"api_key\":\"c\"}");
        var map = new ObjectMapper().readValue(result, java.util.Map.class);
        assertEquals("******", map.get("MQTT_Password"));
        assertEquals("******", map.get("accessToken"));
        assertEquals("******", map.get("api_key"));
    }

    @Test
    void masksNestedKeys() throws Exception {
        String result = redactor.redact("{\"auth\":{\"secret\":\"s\"},\"host\":\"h\"}");
        var map = new ObjectMapper().readValue(result, java.util.Map.class);
        @SuppressWarnings("unchecked")
        var auth = (java.util.Map<String, Object>) map.get("auth");
        assertEquals("******", auth.get("secret"));
        assertEquals("h", map.get("host"));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(redactor.redact(null));
        assertEquals("", redactor.redact(""));
        assertEquals("   ", redactor.redact("   "));
    }

    @Test
    void invalidJsonPassesThrough() {
        String raw = "not json at all";
        assertEquals(raw, redactor.redact(raw));
    }

    @Test
    void noSensitiveFieldsUnchanged() throws Exception {
        String result = redactor.redact("{\"host\":\"h\",\"port\":1883}");
        var map = new ObjectMapper().readValue(result, java.util.Map.class);
        assertEquals("h", map.get("host"));
        assertEquals(1883, map.get("port"));
    }
}
