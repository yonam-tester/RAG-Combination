package com.yeonam.tester;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonam.tester.llm.MockLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase9Tests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testMockLlmClientGeneratesCorrectSupplementaryCount() throws Exception {
        MockLlmClient client = new MockLlmClient();
        String json = client.generateSupplementaryScenarios(
            "요약", "BACKEND", List.of("기존 TC-001", "기존 TC-002"), 3);

        assertNotNull(json);
        List<Map<String, Object>> parsed = objectMapper.readValue(
            json, new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(3, parsed.size());

        for (Map<String, Object> s : parsed) {
            assertNotNull(s.get("testCaseName"));
            assertNotNull(s.get("testScenario"));
            assertNotNull(s.get("expectedResult"));
            assertNotNull(s.get("priority"));
        }
    }

    @Test
    void testMockLlmClientGeneratesOneScenario() throws Exception {
        MockLlmClient client = new MockLlmClient();
        String json = client.generateSupplementaryScenarios("요약", "API", List.of(), 1);
        List<Map<String, Object>> parsed = objectMapper.readValue(
            json, new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(1, parsed.size());
    }
}
