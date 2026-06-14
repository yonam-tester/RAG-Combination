# LLM 멀티 프로바이더 지원 및 MockLlmClient 폐기 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MockLlmClient를 완전히 제거하고 OpenAI 호환 API(OpenAI / LiteLLM / Anthropic proxy)를 지원하는 `OpenAiCompatibleLlmClient`를 추가하여, AWS 자격증명 없이 API 키만으로 시스템을 실행할 수 있도록 한다.

**Architecture:** `LlmClient` 인터페이스를 `BedrockLlmClient`(AWS SDK)와 `OpenAiCompatibleLlmClient`(RestTemplate HTTP) 두 구현체로 유지한다. `LlmClientConfiguration`에서 `llm.provider` 프로퍼티로 선택하며, 미설정 시 `openai`가 기본값이다. 테스트에서는 `@MockBean LlmClient`로 실제 LLM 호출 없이 통합 테스트를 수행한다.

**Tech Stack:** Spring Boot 3.3, RestTemplate, Mockito (@MockBean, @ExtendWith), JUnit 5, Jackson ObjectMapper

---

## 파일 구조

```
삭제:
  backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java

신규:
  backend/src/main/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClient.java
  backend/src/test/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClientTest.java

수정:
  backend/src/main/java/com/yeonam/tester/config/LlmClientConfiguration.java
  backend/src/main/resources/application.yml
  backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java
  backend/src/test/java/com/yeonam/tester/Phase9Tests.java
```

---

## Task 1: Phase9Tests — MockLlmClient 참조 제거 및 @MockBean 전환

**Files:**
- Modify: `backend/src/test/java/com/yeonam/tester/Phase9Tests.java`

- [ ] **Step 1: Phase9Tests 전체를 아래 코드로 교체**

`testMockLlmClientGeneratesCorrectSupplementaryCount`와 `testMockLlmClientGeneratesOneScenario` 두 테스트는 삭제한다. 나머지 5개 테스트는 `@MockBean`을 통해 LLM 호출 없이 실행되도록 전환한다.

```java
package com.yeonam.tester;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.*;
import com.yeonam.tester.llm.LlmClient;
import com.yeonam.tester.repository.*;
import com.yeonam.tester.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@SpringBootTest
class Phase9Tests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockBean
    private LlmClient llmClient;

    @Autowired private ProjectRepository projectRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;
    @Autowired private RequirementRepository requirementRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private ReportService reportService;

    @BeforeEach
    void setUpLlmMock() {
        given(llmClient.generateSupplementaryScenarios(any(), any(), any(), anyInt()))
            .willReturn("""
                [
                  {"testCaseName":"보완 TC-1","testScenario":"시나리오 1","expectedResult":"결과 1","priority":"HIGH"},
                  {"testCaseName":"보완 TC-2","testScenario":"시나리오 2","expectedResult":"결과 2","priority":"MEDIUM"},
                  {"testCaseName":"보완 TC-3","testScenario":"시나리오 3","expectedResult":"결과 3","priority":"LOW"}
                ]
                """);
        given(llmClient.generateTestCases(any(), any(), any()))
            .willReturn("{}");
    }

    @Test
    @Transactional
    void testSupplementaryScenariosSavedWhenTargetCountExceedsExisting() throws Exception {
        Project project = Project.builder()
                .projectId("PRJ-P9-SUPP")
                .name("P9 Supplementary Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P9-SUPP")
                .project(project)
                .status("COMPLETED")
                .summary("로그인 기능 검증 요약")
                .qaPerspective("BACKEND")
                .build();
        analysisJobRepository.saveAndFlush(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-P9-SUPP")
                .analysisJob(job)
                .requirementText("사용자 로그인 요구사항")
                .build();
        requirementRepository.saveAndFlush(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-P9-SUPP-001")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("정상 로그인 검증")
                .testScenario("유효한 계정으로 로그인")
                .expectedResult("로그인 성공")
                .priority("HIGH")
                .build();
        testCaseRepository.saveAndFlush(tc);

        ReportCreateRequest request = ReportCreateRequest.builder()
                .reportFormat("MARKDOWN")
                .targetScenarioCount(4)
                .build();
        ReportResponse response = reportService.generateReport("ANL-P9-SUPP", request);

        Report savedReport = reportRepository.findById(response.getReportId()).orElseThrow();
        assertNotNull(savedReport.getAdditionalScenarios(), "additionalScenarios must be saved");

        List<Map<String, Object>> parsed = objectMapper.readValue(
            savedReport.getAdditionalScenarios(), new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(3, parsed.size(), "Should have generated 3 supplementary scenarios");

        for (Map<String, Object> s : parsed) {
            assertNotNull(s.get("testCaseName"));
            assertNotNull(s.get("testScenario"));
            assertNotNull(s.get("expectedResult"));
            assertNotNull(s.get("priority"));
        }

        reportService.deleteReport(response.getReportId());
    }

    @Test
    @Transactional
    void testNoSupplementaryScenarioWhenTargetCountIsZero() {
        Project project = Project.builder()
                .projectId("PRJ-P9-ZERO")
                .name("P9 Zero Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P9-ZERO")
                .project(project)
                .status("COMPLETED")
                .summary("요약")
                .build();
        analysisJobRepository.saveAndFlush(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-P9-ZERO")
                .analysisJob(job)
                .requirementText("요구사항")
                .build();
        requirementRepository.saveAndFlush(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-P9-ZERO-001")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("기본 케이스")
                .testScenario("시나리오")
                .expectedResult("결과")
                .priority("MEDIUM")
                .build();
        testCaseRepository.saveAndFlush(tc);

        ReportCreateRequest request = ReportCreateRequest.builder()
                .reportFormat("MARKDOWN")
                .targetScenarioCount(0)
                .build();
        ReportResponse response = reportService.generateReport("ANL-P9-ZERO", request);

        Report savedReport = reportRepository.findById(response.getReportId()).orElseThrow();
        assertNull(savedReport.getAdditionalScenarios(), "additionalScenarios must be null when targetCount=0");

        reportService.deleteReport(response.getReportId());
    }

    @Test
    void testTargetScenarioCountOver10ThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
            reportService.generateReport("ANY-ANL-ID", ReportCreateRequest.builder()
                    .reportFormat("MARKDOWN")
                    .targetScenarioCount(11)
                    .build())
        );
    }

    @Test
    @Transactional
    void testMarkdownContainsSupplementarySectionWhenCountExceedsExisting() {
        Project project = Project.builder()
                .projectId("PRJ-P9-RENDER")
                .name("P9 Render Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P9-RENDER")
                .project(project)
                .status("COMPLETED")
                .summary("렌더링 테스트 요약")
                .qaPerspective("API")
                .build();
        analysisJobRepository.saveAndFlush(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-P9-RENDER")
                .analysisJob(job)
                .requirementText("렌더링 요구사항")
                .build();
        requirementRepository.saveAndFlush(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-P9-RENDER-001")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("기본 케이스")
                .testScenario("기본 시나리오")
                .expectedResult("기본 결과")
                .priority("HIGH")
                .build();
        testCaseRepository.saveAndFlush(tc);

        ReportCreateRequest request = ReportCreateRequest.builder()
                .reportFormat("MARKDOWN")
                .targetScenarioCount(3)
                .build();
        ReportResponse response = reportService.generateReport("ANL-P9-RENDER", request);

        var preview = reportService.getReportPreview(response.getReportId());
        String content = preview.getContent();

        assertTrue(content.contains("보완 테스트 시나리오"), "보완 섹션 헤더가 포함되어야 함");
        assertTrue(content.contains("보완-2"), "보완-2 시나리오가 포함되어야 함");
        assertTrue(content.contains("보완-3"), "보완-3 시나리오가 포함되어야 함");

        reportService.deleteReport(response.getReportId());
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn compile -q
```

Expected: `BUILD SUCCESS` (MockLlmClient는 아직 삭제 안 했으므로 컴파일 오류 없음)

- [ ] **Step 3: 커밋**

```bash
git add backend/src/test/java/com/yeonam/tester/Phase9Tests.java
git commit -m "test: Phase9Tests MockLlmClient 직접 참조 제거 및 @MockBean 전환"
```

---

## Task 2: OpenAiCompatibleLlmClient 구현

**Files:**
- Create: `backend/src/main/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClient.java`
- Create: `backend/src/test/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClientTest.java`

- [ ] **Step 1: 단위 테스트 먼저 작성**

`backend/src/test/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClientTest.java` 생성:

```java
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
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=OpenAiCompatibleLlmClientTest -q 2>&1 | tail -5
```

Expected: FAIL with `cannot find symbol: class OpenAiCompatibleLlmClient`

- [ ] **Step 3: OpenAiCompatibleLlmClient 구현**

`backend/src/main/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClient.java` 생성:

```java
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
            text = text.substring(text.indexOf("```json") + 7);
            text = text.substring(0, text.lastIndexOf("```"));
        } else if (text.contains("```")) {
            text = text.substring(text.indexOf("```") + 3);
            text = text.substring(0, text.lastIndexOf("```"));
        }
        return text.strip();
    }
}
```

- [ ] **Step 4: 단위 테스트 실행 — PASS 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=OpenAiCompatibleLlmClientTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClient.java \
        backend/src/test/java/com/yeonam/tester/llm/OpenAiCompatibleLlmClientTest.java
git commit -m "feat: OpenAiCompatibleLlmClient 추가 (OpenAI / LiteLLM / Anthropic proxy 지원)"
```

---

## Task 3: MockLlmClient 삭제 + LlmClientConfiguration 변경 + application.yml 기본값 변경

**Files:**
- Delete: `backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java`
- Modify: `backend/src/main/java/com/yeonam/tester/config/LlmClientConfiguration.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: MockLlmClient.java 삭제**

```bash
rm /Users/rinaeshin/IdeaProjects/RAG-Combination/backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java
```

- [ ] **Step 2: LlmClientConfiguration 전체 교체**

`backend/src/main/java/com/yeonam/tester/config/LlmClientConfiguration.java` 내용:

```java
package com.yeonam.tester.config;

import com.yeonam.tester.llm.BedrockLlmClient;
import com.yeonam.tester.llm.LlmClient;
import com.yeonam.tester.llm.OpenAiCompatibleLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "bedrock")
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient bedrockLlmClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.bedrock.model-id:anthropic.claude-3-haiku-20240307-v1:0}") String modelId
    ) {
        return new BedrockLlmClient(region, modelId);
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient openAiLlmClient(
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model:gpt-4o}") String model
    ) {
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey, model);
    }
}
```

> **왜 @ConditionalOnMissingBean?** Phase9Tests에서 `@MockBean LlmClient`를 선언하면 Spring이 먼저 LlmClient mock 빈을 등록한다. `@ConditionalOnMissingBean(LlmClient.class)`가 true가 되지 않으므로 `openAiLlmClient` 빈 생성 시도 자체가 생략된다. 결과적으로 `${llm.api-key}` 해석이 필요 없어져 테스트에 설정 없이도 컨텍스트 로딩이 성공한다.

- [ ] **Step 3: application.yml llm.provider 기본값 변경**

`backend/src/main/resources/application.yml`에서 아래 라인을 찾아 수정:

```yaml
# 변경 전
llm:
  provider: ${LLM_PROVIDER:mock}
  direct: ${LLM_DIRECT:false}

# 변경 후
llm:
  provider: ${LLM_PROVIDER:openai}
  direct: ${LLM_DIRECT:false}
```

- [ ] **Step 4: 컴파일 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Phase9Tests 실행 — PASS 확인**

```bash
mvn test -Dtest=Phase9Tests -q 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`

> **만약 `Could not resolve placeholder 'llm.api-key'` 오류 발생 시:** `@ConditionalOnMissingBean`이 @MockBean 등록 이전에 평가된 것. 이 경우 Phase9Tests에 `@TestPropertySource(properties = "llm.api-key=test")` 어노테이션 추가.

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/yeonam/tester/config/LlmClientConfiguration.java \
        backend/src/main/resources/application.yml
git rm backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java
git commit -m "feat: MockLlmClient 폐기 및 LlmClientConfiguration openai 기본값 설정"
```

---

## Task 4: BedrockLlmClient generateSupplementaryScenarios 프롬프트 개선

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java:132-198`

- [ ] **Step 1: generateSupplementaryScenarios 메서드의 systemPrompt와 userPrompt 교체**

`BedrockLlmClient.java` 132번째 줄부터 `generateSupplementaryScenarios` 메서드 내부에서 아래 두 변수를 교체한다:

```java
// 변경 전
String systemPrompt = "당신은 QA 아키텍트입니다. 출력은 반드시 마크다운 없는 순수 JSON 배열이어야 합니다.";
String userPrompt = String.format("""
    아래 기존 테스트 케이스와 중복되지 않는 새로운 테스트 케이스를 %d개 생성하세요.

    [기존 테스트 케이스 이름 목록]
    %s

    [QA 관점]
    %s

    [분석 요약]
    %s

    반드시 %d개만 생성하고, 다음 형식의 순수 JSON 배열로만 반환하세요:
    [{"testCaseName":"...","testScenario":"...","expectedResult":"...","priority":"HIGH|MEDIUM|LOW"}]
    """, count, existingNamesStr,
        qaPerspective != null ? qaPerspective : "",
        summary != null ? summary : "",
        count);

// 변경 후
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
```

`max_tokens` 값은 2048 유지 (변경 없음).

- [ ] **Step 2: 전체 테스트 실행**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, 모든 테스트 통과

- [ ] **Step 3: 커밋**

```bash
git add backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java
git commit -m "feat: BedrockLlmClient generateSupplementaryScenarios 프롬프트 개선"
```

---

## Task 5: 최종 확인 및 푸시

- [ ] **Step 1: 전체 빌드 및 테스트**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test 2>&1 | grep -E "Tests run|BUILD|ERROR" | tail -15
```

Expected: `BUILD SUCCESS`, 모든 테스트 통과, `MockLlmClient` 관련 참조 없음

- [ ] **Step 2: MockLlmClient 잔재 없음 확인**

```bash
grep -r "MockLlmClient" /Users/rinaeshin/IdeaProjects/RAG-Combination/backend/src/
```

Expected: 출력 없음 (잔재 없음)

- [ ] **Step 3: 푸시**

```bash
git push origin main
```
