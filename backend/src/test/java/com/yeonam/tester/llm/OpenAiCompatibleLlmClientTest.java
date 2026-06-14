package com.yeonam.tester.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientTest {

    @Mock
    private RestTemplate restTemplate;

    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatibleLlmClient(restTemplate, "https://api.openai.com/v1", "test-key", "gpt-4o");
    }

    private void givenLlmReturns(String content) {
        Map<String, Object> mockResponse = Map.of(
            "choices", List.of(Map.of("message", Map.of("content", content)))
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(mockResponse);
    }

    @Test
    void generateSupplementaryScenarios_returnsJsonFromResponse() {
        String expected = "[{\"testCaseName\":\"TC-1\",\"testScenario\":\"시나리오\",\"expectedResult\":\"결과\",\"priority\":\"HIGH\"}]";
        givenLlmReturns(expected);

        String result = client.generateSupplementaryScenarios("요약", "BACKEND", List.of("기존TC"), 1);

        assertEquals(expected, result);
    }

    @Test
    void generateSupplementaryScenarios_stripsMarkdownCodeBlock() {
        String content = "```json\n[{\"testCaseName\":\"TC-1\",\"testScenario\":\"s\",\"expectedResult\":\"r\",\"priority\":\"HIGH\"}]\n```";
        givenLlmReturns(content);

        String result = client.generateSupplementaryScenarios("요약", "BACKEND", List.of(), 1);

        assertFalse(result.contains("```"), "마크다운 코드블록이 제거되어야 함");
        assertTrue(result.startsWith("["), "JSON 배열로 시작해야 함");
    }

    @Test
    void generateTestCases_returnsContentFromResponse() {
        String expected = "{\"summary\":\"요약\",\"testCases\":[]}";
        givenLlmReturns(expected);

        String result = client.generateTestCases("요구사항", null, "BACKEND");

        assertEquals(expected, result);
    }

    @Test
    void generateTestCases_stripsMarkdownCodeBlock() {
        givenLlmReturns("```\n{\"summary\":\"s\",\"testCases\":[]}\n```");

        String result = client.generateTestCases("요구사항", null, "BACKEND");

        assertFalse(result.contains("```"));
        assertTrue(result.startsWith("{"));
    }
}
