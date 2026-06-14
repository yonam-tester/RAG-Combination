package com.yeonam.tester.llm;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model) {
        this(new RestTemplate(), baseUrl, apiKey, model);
    }

    OpenAiCompatibleLlmClient(RestTemplate restTemplate, String baseUrl, String apiKey, String model) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String generateTestCases(String requirementText, String customPrompt, String qaPerspective) {
        String systemPrompt = """
                당신은 요구사항 설계 명세를 분석하여 고품질의 QA/TDD용 테스트 케이스와 기획 누락점을 추출하는 전문 QA 아키텍트입니다.
                출력은 반드시 마크다운 기호(```json ... ```)가 없는 순수 JSON 단일 문자열이어야 합니다.

                [출력 JSON 규격]
                {
                  "summary": "분석 요약 글",
                  "missingItems": ["기획 누락 분석 텍스트 1"],
                  "testCases": [
                    {
                      "testCaseId": "TC-xxx",
                      "requirementId": "REQ-xxx",
                      "requirementText": "요구사항 텍스트 문구",
                      "testCaseName": "테스트 케이스 이름",
                      "testScenario": "테스트 시나리오",
                      "precondition": "사전 조건",
                      "testSteps": "1. 단계 1\\n2. 단계 2",
                      "expectedResult": "기대 결과",
                      "priority": "HIGH / MEDIUM / LOW",
                      "confidenceLevel": "HIGH / MEDIUM / LOW",
                      "riskTags": ["#태그1", "#태그2"],
                      "category": "functional / security / performance / non_functional",
                      "technique": "적용한 테스트 설계 기법 명칭",
                      "tddHint": "Assert 등을 이용한 개발자 TDD 힌트",
                      "negativeScenario": "예외/부정 검증 시나리오",
                      "caution": "테스트 수행 시의 주의 사항 또는 환경 제약 사항 설명"
                    }
                  ]
                }
                """;

        String userPrompt = String.format("""
                [요구사항 명세 원문]
                %s

                [활성화된 QA 검증 관점]
                %s

                [사용자 커스텀 추가 요청]
                %s
                """, requirementText,
                qaPerspective != null ? qaPerspective : "",
                customPrompt != null ? customPrompt : "");

        return call(systemPrompt, userPrompt, 4096);
    }

    @Override
    public String generateSupplementaryScenarios(String summary, String qaPerspective,
                                                  List<String> existingNames, int count) {
        String existingNamesStr = (existingNames == null || existingNames.isEmpty())
            ? "(없음)"
            : existingNames.stream().map(n -> "- " + n).collect(Collectors.joining("\n"));

        String systemPrompt = """
                당신은 소프트웨어 QA 아키텍트입니다. 기존 테스트 케이스의 검증 사각지대를 보완하는
                추가 테스트 시나리오를 설계합니다.
                출력은 반드시 마크다운 없는 순수 JSON 배열만 반환하십시오.
                """;

        String userPrompt = String.format("""
                [분석 요약]
                %s

                [QA 검증 관점]
                %s

                [기존 TC 목록] (아래 항목과 중복되지 않게 생성하세요)
                %s

                반드시 %d개만 생성하세요. 형식:
                [{"testCaseName":"...","testScenario":"...","expectedResult":"...","priority":"HIGH|MEDIUM|LOW"}]
                """,
            summary != null ? summary : "",
            qaPerspective != null ? qaPerspective : "",
            existingNamesStr,
            count);

        return call(systemPrompt, userPrompt, 2048);
    }

    private String call(String systemPrompt, String userPrompt, int maxTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                baseUrl + "/chat/completions", entity, Map.class);

            if (response == null) {
                throw new RuntimeException("OpenAI 호환 LLM 호출 실패: 응답이 null입니다 (HTTP 응답 본문 없음)");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            return stripMarkdownCodeBlock(content);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 호환 LLM 호출 실패: " + e.getMessage(), e);
        }
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text.contains("```json")) {
            int start = text.indexOf("```json") + 7;
            int end = text.lastIndexOf("```");
            if (end > start) {
                text = text.substring(start, end);
            }
        } else if (text.contains("```")) {
            int start = text.indexOf("```") + 3;
            int end = text.lastIndexOf("```");
            if (end > start) {
                text = text.substring(start, end);
            }
        }
        return text.strip();
    }
}
