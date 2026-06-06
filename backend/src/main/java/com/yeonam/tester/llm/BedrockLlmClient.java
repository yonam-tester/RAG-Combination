package com.yeonam.tester.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;

public class BedrockLlmClient implements LlmClient {

    private final BedrockRuntimeClient bedrockClient;
    private final String modelId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BedrockLlmClient(String region, String modelId) {
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .build();
        this.modelId = modelId;
    }

    @Override
    public String generateTestCases(String requirementText, String customPrompt, String qaPerspective) {
        try {
            // Claude 3용 시스템 및 유저 프롬프트 설정
            String systemPrompt = """
                    당신은 요구사항 설계 명세를 분석하여 고품질의 QA/TDD용 테스트 케이스와 기획 누락점을 추출하는 전문 QA 아키텍트입니다.
                    출력은 반드시 마크다운 기호(```json ... ```)가 없는 순수 JSON 단일 문자열이어야 합니다.
                    
                    [출력 JSON 규격]
                    {
                      "summary": "분석 요약 글",
                      "missingItems": [
                        "기획 누락 분석 텍스트 1",
                        "기획 누락 분석 텍스트 2"
                      ],
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
                          "technique": "적용한 Atlassian 설계 기법 명칭",
                          "tddHint": "Assert 등을 이용한 개발자 TDD 힌트",
                          "negativeScenario": "예외/부정 검증 시나리오",
                          "caution": "테스트 수행 시의 주의 사항 또는 환경 제약 사항 설명(가정에 근거한 부분이 있다면 경고 기입)"
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
                    """, requirementText, qaPerspective, customPrompt);

            // Claude 3 Invoke API 바디 조립 (Jackson 이용)
            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("anthropic_version", "bedrock-2023-05-31");
            rootNode.put("max_tokens", 4096);
            rootNode.put("system", systemPrompt);

            ArrayNode messagesArray = objectMapper.createArrayNode();
            ObjectNode messageNode = objectMapper.createObjectNode();
            messageNode.put("role", "user");
            
            ArrayNode contentArray = objectMapper.createArrayNode();
            ObjectNode textContentNode = objectMapper.createObjectNode();
            textContentNode.put("type", "text");
            textContentNode.put("text", userPrompt);
            contentArray.add(textContentNode);
            
            messageNode.set("content", contentArray);
            messagesArray.add(messageNode);
            rootNode.set("messages", messagesArray);

            String requestBody = objectMapper.writeValueAsString(rootNode);

            // AWS Bedrock 호출 요청 생성
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .build();

            InvokeModelResponse response = bedrockClient.invokeModel(request);
            
            // 결과 바디 파싱
            String responseBody = response.body().asString(StandardCharsets.UTF_8);
            JsonNode responseJson = objectMapper.readTree(responseBody);
            
            // content[0].text 필드에서 LLM 생성 결과 문자열 추출
            String extractedText = responseJson.path("content").get(0).path("text").asText();
            
            // 혹시 Claude가 마크다운 블록(```json)을 씌워서 줬다면 정제
            if (extractedText.contains("```json")) {
                extractedText = extractedText.substring(extractedText.indexOf("```json") + 7);
                extractedText = extractedText.substring(0, extractedText.lastIndexOf("```"));
            } else if (extractedText.contains("```")) {
                extractedText = extractedText.substring(extractedText.indexOf("```") + 3);
                extractedText = extractedText.substring(0, extractedText.lastIndexOf("```"));
            }
            
            return extractedText.strip();
        } catch (Exception e) {
            throw new RuntimeException("AWS Bedrock LLM 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }
}
