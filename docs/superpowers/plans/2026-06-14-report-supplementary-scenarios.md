# 보고서 보완 시나리오 생성 기능 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보고서 생성 시 `targetScenarioCount`(2~10)를 지정하면 기존 TestCase가 부족할 경우 LLM을 추가 호출해 보완 시나리오를 생성하고 보고서에 합산 출력한다.

**Architecture:** `ReportCreateRequest`에 `targetScenarioCount` 필드를 추가하고, `ReportService.generateReport()`에서 보완 시나리오가 필요할 때 `LlmClient.generateSupplementaryScenarios()`를 호출해 결과를 `Report.additionalScenarios`(TEXT 컬럼)에 JSON으로 저장한다. `ReportRenderEngine`은 model map의 `additionalScenarios` 키를 읽어 보완 섹션을 렌더링한다. LLM 호출 실패 시 기존 TC만으로 보고서를 생성하는 graceful fallback을 제공한다.

**Tech Stack:** Spring Boot (Java 17), JPA/H2, AWS Bedrock SDK (Claude 3), React/TypeScript

---

## 파일 변경 맵

| 파일 | 변경 유형 | 역할 |
|---|---|---|
| `backend/src/main/java/com/yeonam/tester/dto/ReportCreateRequest.java` | 수정 | `targetScenarioCount` 필드 추가 |
| `backend/src/main/java/com/yeonam/tester/dto/SupplementaryScenarioDto.java` | 신규 생성 | 보완 시나리오 JSON 역직렬화 DTO |
| `backend/src/main/java/com/yeonam/tester/domain/Report.java` | 수정 | `additional_scenarios TEXT` 컬럼 추가 |
| `backend/src/main/java/com/yeonam/tester/llm/LlmClient.java` | 수정 | `generateSupplementaryScenarios()` 메서드 추가 |
| `backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java` | 수정 | `generateSupplementaryScenarios()` mock 구현 |
| `backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java` | 수정 | `generateSupplementaryScenarios()` Bedrock 구현 |
| `backend/src/main/java/com/yeonam/tester/service/ReportService.java` | 수정 | 보완 시나리오 생성/저장/모델 주입 로직 |
| `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java` | 수정 | 보완 시나리오 섹션 마크다운 렌더링 |
| `backend/src/test/java/com/yeonam/tester/Phase9Tests.java` | 신규 생성 | 보완 시나리오 통합 테스트 |
| `frontend/src/services/api.ts` | 수정 | `targetScenarioCount` 타입 및 API 호출 파라미터 추가 |
| `frontend/src/pages/AnalysisResultPage.tsx` | 수정 | 시나리오 수 입력 UI 추가 |

---

## Task 1: ReportCreateRequest에 targetScenarioCount 추가

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/dto/ReportCreateRequest.java`

- [ ] **Step 1: 파일 전체를 다음으로 교체**

```java
package com.yeonam.tester.dto;

import java.util.List;

public class ReportCreateRequest {
    private String reportFormat;
    private List<String> testCaseIds;
    private int targetScenarioCount;

    public ReportCreateRequest() {}

    public ReportCreateRequest(String reportFormat, List<String> testCaseIds, int targetScenarioCount) {
        this.reportFormat = reportFormat;
        this.testCaseIds = testCaseIds;
        this.targetScenarioCount = targetScenarioCount;
    }

    public String getReportFormat() { return reportFormat; }
    public void setReportFormat(String reportFormat) { this.reportFormat = reportFormat; }

    public List<String> getTestCaseIds() { return testCaseIds; }
    public void setTestCaseIds(List<String> testCaseIds) { this.testCaseIds = testCaseIds; }

    public int getTargetScenarioCount() { return targetScenarioCount; }
    public void setTargetScenarioCount(int targetScenarioCount) { this.targetScenarioCount = targetScenarioCount; }

    public static ReportCreateRequestBuilder builder() {
        return new ReportCreateRequestBuilder();
    }

    public static class ReportCreateRequestBuilder {
        private String reportFormat;
        private List<String> testCaseIds;
        private int targetScenarioCount;

        public ReportCreateRequestBuilder reportFormat(String reportFormat) {
            this.reportFormat = reportFormat;
            return this;
        }

        public ReportCreateRequestBuilder testCaseIds(List<String> testCaseIds) {
            this.testCaseIds = testCaseIds;
            return this;
        }

        public ReportCreateRequestBuilder targetScenarioCount(int targetScenarioCount) {
            this.targetScenarioCount = targetScenarioCount;
            return this;
        }

        public ReportCreateRequest build() {
            return new ReportCreateRequest(reportFormat, testCaseIds, targetScenarioCount);
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS (기존 코드는 `new ReportCreateRequest(format, ids)` 생성자 대신 builder 또는 no-arg+setter 방식을 쓰므로 하위 호환)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/dto/ReportCreateRequest.java
git commit -m "feat: ReportCreateRequest에 targetScenarioCount 필드 추가"
```

---

## Task 2: SupplementaryScenarioDto 생성

**Files:**
- Create: `backend/src/main/java/com/yeonam/tester/dto/SupplementaryScenarioDto.java`

- [ ] **Step 1: 파일 생성**

```java
package com.yeonam.tester.dto;

public class SupplementaryScenarioDto {
    private String testCaseName;
    private String testScenario;
    private String expectedResult;
    private String priority;

    public SupplementaryScenarioDto() {}

    public SupplementaryScenarioDto(String testCaseName, String testScenario, String expectedResult, String priority) {
        this.testCaseName = testCaseName;
        this.testScenario = testScenario;
        this.expectedResult = expectedResult;
        this.priority = priority;
    }

    public String getTestCaseName() { return testCaseName; }
    public void setTestCaseName(String testCaseName) { this.testCaseName = testCaseName; }

    public String getTestScenario() { return testScenario; }
    public void setTestScenario(String testScenario) { this.testScenario = testScenario; }

    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/dto/SupplementaryScenarioDto.java
git commit -m "feat: 보완 시나리오 역직렬화용 SupplementaryScenarioDto 추가"
```

---

## Task 3: Report 도메인에 additionalScenarios 컬럼 추가

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/domain/Report.java`

- [ ] **Step 1: 파일 전체를 다음으로 교체**

```java
package com.yeonam.tester.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "report")
public class Report {

    @Id
    @Column(name = "report_id")
    private String reportId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", nullable = false)
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = true)
    private UploadedFile uploadedFile;

    @Column(name = "s3_path", nullable = false)
    private String s3Path;

    @Column(name = "format", nullable = false)
    private String format;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Lob
    @Column(name = "additional_scenarios", columnDefinition = "CLOB")
    private String additionalScenarios;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<ReportTestCase> reportTestCases = new java.util.ArrayList<>();

    public Report() {}

    public Report(String reportId, AnalysisJob analysisJob, UploadedFile uploadedFile, String s3Path, String format, LocalDateTime createdAt, String additionalScenarios) {
        this.reportId = reportId;
        this.analysisJob = analysisJob;
        this.uploadedFile = uploadedFile;
        this.s3Path = s3Path;
        this.format = format;
        this.createdAt = createdAt;
        this.additionalScenarios = additionalScenarios;
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public AnalysisJob getAnalysisJob() { return analysisJob; }
    public void setAnalysisJob(AnalysisJob analysisJob) { this.analysisJob = analysisJob; }

    public UploadedFile getUploadedFile() { return uploadedFile; }
    public void setUploadedFile(UploadedFile uploadedFile) { this.uploadedFile = uploadedFile; }

    public String getS3Path() { return s3Path; }
    public void setS3Path(String s3Path) { this.s3Path = s3Path; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getAdditionalScenarios() { return additionalScenarios; }
    public void setAdditionalScenarios(String additionalScenarios) { this.additionalScenarios = additionalScenarios; }

    public java.util.List<ReportTestCase> getReportTestCases() { return reportTestCases; }
    public void setReportTestCases(java.util.List<ReportTestCase> reportTestCases) { this.reportTestCases = reportTestCases; }

    public static ReportBuilder builder() {
        return new ReportBuilder();
    }

    public static class ReportBuilder {
        private String reportId;
        private AnalysisJob analysisJob;
        private UploadedFile uploadedFile;
        private String s3Path;
        private String format;
        private LocalDateTime createdAt;
        private String additionalScenarios;

        public ReportBuilder reportId(String reportId) { this.reportId = reportId; return this; }
        public ReportBuilder analysisJob(AnalysisJob analysisJob) { this.analysisJob = analysisJob; return this; }
        public ReportBuilder uploadedFile(UploadedFile uploadedFile) { this.uploadedFile = uploadedFile; return this; }
        public ReportBuilder s3Path(String s3Path) { this.s3Path = s3Path; return this; }
        public ReportBuilder format(String format) { this.format = format; return this; }
        public ReportBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ReportBuilder additionalScenarios(String additionalScenarios) { this.additionalScenarios = additionalScenarios; return this; }

        public Report build() {
            return new Report(reportId, analysisJob, uploadedFile, s3Path, format, createdAt, additionalScenarios);
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS (H2 스키마는 `spring.jpa.hibernate.ddl-auto=create-drop` 또는 `update`이므로 새 컬럼이 자동 반영됨)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/domain/Report.java
git commit -m "feat: Report 도메인에 additional_scenarios CLOB 컬럼 추가"
```

---

## Task 4: LlmClient 인터페이스에 generateSupplementaryScenarios() 추가

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/llm/LlmClient.java`

- [ ] **Step 1: 파일 전체를 다음으로 교체**

```java
package com.yeonam.tester.llm;

import java.util.List;

public interface LlmClient {
    String generateTestCases(String requirementText, String customPrompt, String qaPerspective);

    /**
     * 기존 테스트 케이스와 중복 없이 보완 시나리오를 count개 생성한다.
     * 반환값은 JSON 배열 문자열: [{"testCaseName":"...","testScenario":"...","expectedResult":"...","priority":"..."}]
     */
    String generateSupplementaryScenarios(String summary, String qaPerspective, List<String> existingNames, int count);
}
```

- [ ] **Step 2: 컴파일 시도 (MockLlmClient, BedrockLlmClient 미구현으로 실패 확인)**

```bash
cd backend && ./mvnw compile 2>&1 | grep "error\|ERROR" | head -10
```

Expected: `MockLlmClient is not abstract and does not override abstract method` 형태의 컴파일 에러 확인

- [ ] **Step 3: Commit (인터페이스만 먼저 커밋)**

```bash
git add backend/src/main/java/com/yeonam/tester/llm/LlmClient.java
git commit -m "feat: LlmClient에 generateSupplementaryScenarios() 인터페이스 추가"
```

---

## Task 5: MockLlmClient 구현 — TDD

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java`
- Test: `backend/src/test/java/com/yeonam/tester/Phase9Tests.java` (일부 — MockLlmClient 단위 테스트)

- [ ] **Step 1: Phase9Tests.java 생성 — MockLlmClient 단위 테스트 먼저 작성**

```java
package com.yeonam.tester;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.*;
import com.yeonam.tester.llm.MockLlmClient;
import com.yeonam.tester.repository.*;
import com.yeonam.tester.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
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
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./mvnw test -Dtest=Phase9Tests#testMockLlmClientGeneratesCorrectSupplementaryCount -pl . 2>&1 | tail -20
```

Expected: 컴파일 에러 (MockLlmClient 미구현)

- [ ] **Step 3: MockLlmClient.java 전체를 다음으로 교체**

```java
package com.yeonam.tester.llm;

import java.util.List;

public class MockLlmClient implements LlmClient {

    @Override
    public String generateTestCases(String requirementText, String customPrompt, String qaPerspective) {
        return """
                {
                  "summary": "업로드된 명세서 텍스트를 기반으로 사용자 인증 및 권한 제어 관련 테스트 케이스를 자동 분석하였습니다.",
                  "missingItems": [
                    "사용자 비밀번호 변경 시 이전 비밀번호 확인 단계 누락",
                    "로그인 연속 실패 시 계정 잠금 정책 및 잠금 해제 조건 명세 미비"
                  ],
                  "testCases": [
                    {
                      "testCaseId": "TC-001",
                      "requirementId": "REQ-001",
                      "requirementText": "사용자는 유효한 계정 정보(이메일, 비밀번호)를 입력하여 시스템에 로그인할 수 있어야 한다.",
                      "testCaseName": "정상 로그인 및 세션 생성 검증",
                      "testScenario": "유효한 이메일과 비밀번호로 로그인 요청 시, 세션 토큰 반환 및 메인 화면 진입 여부 확인",
                      "precondition": "회원가입이 완료된 활성화 상태의 테스트 계정이 DB에 존재해야 함",
                      "testSteps": "1. 로그인 화면에 진입한다.\\n2. 유효한 이메일('test@example.com')과 비밀번호('Password123!')를 입력한다.\\n3. 로그인 버튼을 클릭한다.",
                      "expectedResult": "로그인에 성공하고 JWT 세션 토큰이 발급되며 메인 대시보드로 정상 이동한다.",
                      "priority": "HIGH",
                      "confidenceLevel": "HIGH",
                      "riskTags": ["#인증_성공", "#세션_토큰_발급"],
                      "category": "functional",
                      "technique": "Happy Path Testing",
                      "tddHint": "assertEquals(HttpStatus.OK, response.getStatusCode());\\nassertNotNull(response.getBody().getToken());",
                      "negativeScenario": "탈퇴한 계정 정보로 로그인 시도 시, HTTP 401 Unauthorized 에러와 함께 '탈퇴 처리된 계정입니다' 경고가 출력되는지 검증.",
                      "caution": "비밀번호 암호화 강도나 PBKDF2 반복 횟수 같은 세부 보안 셋업은 인프라 설정에 의존하므로 로컬 개발 모드 실행 시 점검이 필수적입니다."
                    },
                    {
                      "testCaseId": "TC-002",
                      "requirementId": "REQ-001",
                      "requirementText": "사용자는 유효한 계정 정보(이메일, 비밀번호)를 입력하여 시스템에 로그인할 수 있어야 한다.",
                      "testCaseName": "유효하지 않은 이메일 형식 차단 검증",
                      "testScenario": "이메일 골뱅이(@) 기호 누락 등 비정상 포맷 입력 시 프론트엔드 및 백엔드 이중 유효성 차단 확인",
                      "precondition": "로그인 화면에 접근한 상태",
                      "testSteps": "1. 이메일 입력 칸에 'invalid-email-format'을 기입한다.\\n2. 비밀번호를 입력한다.\\n3. 로그인 단추를 누른다.",
                      "expectedResult": "제출 버튼이 비활성화되거나, 클릭 시 즉각 '올바른 이메일 형식이 아닙니다' 유효성 경고창이 팝업된다.",
                      "priority": "MEDIUM",
                      "confidenceLevel": "HIGH",
                      "riskTags": ["#입력값_유효성", "#인증_실패"],
                      "category": "test_technique",
                      "technique": "Boundary Value Analysis",
                      "tddHint": "assertThrows(MethodArgumentNotValidException.class, () -> authController.login(request));",
                      "negativeScenario": "SQL Injection 시도값(admin' --)을 이메일 필드에 입력하여 제출 시, 안전하게 특수문자가 이스케이프 처리되며 쿼리 에러 없이 로그인 거부되는지 검증.",
                      "caution": "프론트엔드 유효성 필터링은 이메일 정규식 포맷에 맞춘 클라이언트 단 조작이 가능하므로, 항상 백엔드 API 컨트롤러 단독 검증 테스트를 통과해야 합니다."
                    }
                  ]
                }
                """;
    }

    @Override
    public String generateSupplementaryScenarios(String summary, String qaPerspective, List<String> existingNames, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            int num = i + 1;
            sb.append("{")
              .append("\"testCaseName\":\"보완 시나리오 ").append(num).append("\",")
              .append("\"testScenario\":\"보완 시나리오 ").append(num).append("에 대한 검증 시나리오\",")
              .append("\"expectedResult\":\"보완 시나리오 ").append(num).append("의 기대 결과\",")
              .append("\"priority\":\"MEDIUM\"")
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
cd backend && ./mvnw test -Dtest=Phase9Tests#testMockLlmClientGeneratesCorrectSupplementaryCount,Phase9Tests#testMockLlmClientGeneratesOneScenario 2>&1 | tail -15
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/llm/MockLlmClient.java \
        backend/src/test/java/com/yeonam/tester/Phase9Tests.java
git commit -m "feat: MockLlmClient.generateSupplementaryScenarios() 구현 및 단위 테스트 추가"
```

---

## Task 6: BedrockLlmClient 구현

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java`

(BedrockLlmClient는 외부 AWS 서비스 의존이므로 단위 테스트 없이 구현만 추가한다.)

- [ ] **Step 1: `generateSupplementaryScenarios()` 메서드를 BedrockLlmClient 클래스에 추가**

`BedrockLlmClient.java`의 `generateTestCases()` 메서드 닫는 중괄호 직후(클래스 닫기 전)에 다음을 추가:

```java
@Override
public String generateSupplementaryScenarios(String summary, String qaPerspective, List<String> existingNames, int count) {
    try {
        String existingNamesStr = (existingNames == null || existingNames.isEmpty())
            ? "(없음)"
            : existingNames.stream().map(n -> "- " + n).collect(java.util.stream.Collectors.joining("\n"));

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

        ObjectNode rootNode = objectMapper.createObjectNode();
        rootNode.put("anthropic_version", "bedrock-2023-05-31");
        rootNode.put("max_tokens", 2048);
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
        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .body(SdkBytes.fromUtf8String(requestBody))
                .build();

        InvokeModelResponse response = bedrockClient.invokeModel(request);
        String responseBody = response.body().asString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode responseJson = objectMapper.readTree(responseBody);
        String extractedText = responseJson.path("content").get(0).path("text").asText();

        if (extractedText.contains("```json")) {
            extractedText = extractedText.substring(extractedText.indexOf("```json") + 7);
            extractedText = extractedText.substring(0, extractedText.lastIndexOf("```"));
        } else if (extractedText.contains("```")) {
            extractedText = extractedText.substring(extractedText.indexOf("```") + 3);
            extractedText = extractedText.substring(0, extractedText.lastIndexOf("```"));
        }
        return extractedText.strip();
    } catch (Exception e) {
        throw new RuntimeException("AWS Bedrock 보완 시나리오 생성 실패: " + e.getMessage(), e);
    }
}
```

주의: `BedrockLlmClient.java` 상단에 이미 `import java.util.List;`가 없다면 추가 필요. 기존 import 블록을 확인하고 `import java.util.List;`를 추가한다.

- [ ] **Step 2: 컴파일 확인**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/llm/BedrockLlmClient.java
git commit -m "feat: BedrockLlmClient.generateSupplementaryScenarios() 구현"
```

---

## Task 7: ReportService — 보완 시나리오 생성/저장/모델 주입 — TDD

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/ReportService.java`
- Test: `backend/src/test/java/com/yeonam/tester/Phase9Tests.java` (통합 테스트 추가)

- [ ] **Step 1: Phase9Tests.java에 통합 테스트 추가 (기존 파일에 메서드 append)**

Phase9Tests 클래스에 다음 필드와 테스트를 추가:

```java
// 클래스 상단 @Autowired 목록에 추가:
@Autowired
private ProjectRepository projectRepository;

@Autowired
private AnalysisJobRepository analysisJobRepository;

@Autowired
private RequirementRepository requirementRepository;

@Autowired
private TestCaseRepository testCaseRepository;

@Autowired
private ReportRepository reportRepository;

@Autowired
private ReportService reportService;

// 테스트 메서드 추가:
@Test
@Transactional
void testSupplementaryScenariosSavedWhenTargetCountExceedsExisting() throws Exception {
    // 1. Setup: project, job with 1 TestCase
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

    // 2. Generate report with targetScenarioCount=4 (existing=1, need 3 more)
    ReportCreateRequest request = ReportCreateRequest.builder()
            .reportFormat("MARKDOWN")
            .targetScenarioCount(4)
            .build();
    ReportResponse response = reportService.generateReport("ANL-P9-SUPP", request);

    // 3. Verify additionalScenarios saved in Report
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

    // Cleanup
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

    // targetScenarioCount=0 → no LLM call
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
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./mvnw test -Dtest=Phase9Tests#testSupplementaryScenariosSavedWhenTargetCountExceedsExisting 2>&1 | tail -20
```

Expected: FAIL — `Report` 에 `additionalScenarios` 없거나 `ReportService`에 로직 없음

- [ ] **Step 3: ReportService.java 수정 — import 추가**

파일 상단 import 블록에 다음을 추가:

```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonam.tester.dto.SupplementaryScenarioDto;
import com.yeonam.tester.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
```

- [ ] **Step 4: ReportService 클래스 본문 수정 — 필드 및 생성자 업데이트**

기존 필드 목록 끝에 추가:

```java
private static final Logger log = LoggerFactory.getLogger(ReportService.class);
private final LlmClient llmClient;
private final ObjectMapper objectMapper;
```

기존 생성자를 다음으로 교체:

```java
public ReportService(ReportRepository reportRepository,
                     AnalysisJobRepository analysisJobRepository,
                     ReportAssemblyService assemblyService,
                     ReportRenderEngine renderEngine,
                     S3Client s3Client,
                     ReportTestCaseRepository reportTestCaseRepository,
                     TestCaseRepository testCaseRepository,
                     LlmClient llmClient,
                     ObjectMapper objectMapper) {
    this.reportRepository = reportRepository;
    this.analysisJobRepository = analysisJobRepository;
    this.assemblyService = assemblyService;
    this.renderEngine = renderEngine;
    this.s3Client = s3Client;
    this.reportTestCaseRepository = reportTestCaseRepository;
    this.testCaseRepository = testCaseRepository;
    this.llmClient = llmClient;
    this.objectMapper = objectMapper;
}
```

- [ ] **Step 5: ReportService.generateReport() 메서드에 보완 시나리오 로직 삽입**

기존 `generateReport()` 메서드에서 format 유효성 검사 직후, `AnalysisJob job = ...` 조회 직전에 다음을 추가:

```java
// targetScenarioCount 유효성 검사
int targetCount = request.getTargetScenarioCount();
if (targetCount > 10) {
    throw new IllegalArgumentException("targetScenarioCount는 10 이하여야 합니다.");
}
```

그리고 `Map<String, Object> data = assemblyService.assembleReportData(analysisId, request.getTestCaseIds());` 라인 직전, `AnalysisJob job = ...` 조회 직후에 다음 블록을 삽입:

```java
// 보완 시나리오 생성 (targetCount > 현재 TC 수인 경우)
List<TestCase> existingTcs;
if (request.getTestCaseIds() == null || request.getTestCaseIds().isEmpty()) {
    existingTcs = testCaseRepository.findByAnalysisJob_AnalysisId(analysisId);
} else {
    existingTcs = testCaseRepository.findAllById(request.getTestCaseIds());
}

List<Map<String, Object>> additionalScenarioMaps = new ArrayList<>();
String additionalScenariosJson = null;

if (targetCount > 0 && targetCount > existingTcs.size()) {
    int needed = targetCount - existingTcs.size();
    List<String> existingNames = existingTcs.stream()
            .map(com.yeonam.tester.domain.TestCase::getTestCaseName)
            .collect(Collectors.toList());
    try {
        String llmResponse = llmClient.generateSupplementaryScenarios(
                job.getSummary(), job.getQaPerspective(), existingNames, needed);
        List<SupplementaryScenarioDto> parsed = objectMapper.readValue(
                llmResponse, new TypeReference<List<SupplementaryScenarioDto>>() {});
        if (parsed.size() > needed) {
            parsed = parsed.subList(0, needed);
        }
        additionalScenariosJson = objectMapper.writeValueAsString(parsed);
        for (SupplementaryScenarioDto s : parsed) {
            Map<String, Object> m = new HashMap<>();
            m.put("testCaseName", s.getTestCaseName());
            m.put("testScenario", s.getTestScenario());
            m.put("expectedResult", s.getExpectedResult());
            m.put("priority", s.getPriority() != null ? s.getPriority() : "MEDIUM");
            additionalScenarioMaps.add(m);
        }
    } catch (Exception e) {
        log.warn("보완 시나리오 LLM 생성 실패, 기존 TC만으로 보고서 생성: {}", e.getMessage());
    }
}
```

그리고 `assembleReportData` 호출 직후, `renderEngine.renderMarkdown(data)` 직전에:

```java
data.put("additionalScenarios", additionalScenarioMaps);
```

그리고 `Report report = Report.builder()` 빌더에 `.additionalScenarios(additionalScenariosJson)` 추가:

```java
Report report = Report.builder()
        .reportId(reportId)
        .analysisJob(job)
        .s3Path(s3Path)
        .format("MARKDOWN")
        .createdAt(LocalDateTime.now())
        .additionalScenarios(additionalScenariosJson)
        .build();
```

- [ ] **Step 6: ReportService.getReportPreview()의 재생성 경로에 additionalScenarios 주입**

`getReportPreview()` 내에서 `assemblyService.assembleReportData(...)` 를 호출하는 코드가 총 3곳(PDF 경로, local 파일 유실 경로, S3 유실 경로) 있다. 각 경로에서 `data = assemblyService.assembleReportData(...)` 직후에 다음을 추가:

```java
data.put("additionalScenarios", parseAdditionalScenariosToMaps(report.getAdditionalScenarios()));
```

그리고 `getReportPreview()` 메서드 아래(또는 private 영역)에 헬퍼 메서드 추가:

```java
private List<Map<String, Object>> parseAdditionalScenariosToMaps(String json) {
    if (json == null || json.isBlank()) return Collections.emptyList();
    try {
        List<SupplementaryScenarioDto> dtos = objectMapper.readValue(
            json, new TypeReference<List<SupplementaryScenarioDto>>() {});
        List<Map<String, Object>> result = new ArrayList<>();
        for (SupplementaryScenarioDto s : dtos) {
            Map<String, Object> m = new HashMap<>();
            m.put("testCaseName", s.getTestCaseName());
            m.put("testScenario", s.getTestScenario());
            m.put("expectedResult", s.getExpectedResult());
            m.put("priority", s.getPriority() != null ? s.getPriority() : "MEDIUM");
            result.add(m);
        }
        return result;
    } catch (Exception e) {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 7: 기존 보고서 생성 경로에서 data.put 호출 위치 확인**

`generateReport()` 내에서 data를 사용하는 흐름이 위 변경으로 올바른지 컴파일로 확인:

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 8: 테스트 실행 — 통과 확인**

```bash
cd backend && ./mvnw test -Dtest=Phase9Tests 2>&1 | tail -20
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 9: 기존 테스트 회귀 확인**

```bash
cd backend && ./mvnw test -Dtest=Phase6Tests 2>&1 | tail -10
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/service/ReportService.java \
        backend/src/test/java/com/yeonam/tester/Phase9Tests.java
git commit -m "feat: ReportService에 보완 시나리오 LLM 생성 및 저장 로직 추가"
```

---

## Task 8: ReportRenderEngine — 보완 시나리오 섹션 렌더링

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java`

- [ ] **Step 1: renderMarkdown() 메서드에서 기존 testCases 섹션 렌더링 완료 직후, Appendix 섹션 직전에 다음 블록 삽입**

`// 4. Appendix: Omissions and Disclaimer` 주석 바로 위에:

```java
// 4. 보완 시나리오 섹션 (additionalScenarios가 있을 경우만)
List<Map<String, Object>> additionalScenarios =
        (List<Map<String, Object>>) model.getOrDefault("additionalScenarios", Collections.emptyList());
if (!additionalScenarios.isEmpty()) {
    sb.append("## 4. 보완 테스트 시나리오 (LLM 추가 생성)\n\n");
    int baseIndex = testCases.size() + 1;
    for (int i = 0; i < additionalScenarios.size(); i++) {
        Map<String, Object> s = additionalScenarios.get(i);
        sb.append("### 보완-").append(baseIndex + i).append(": ")
          .append(s.getOrDefault("testCaseName", "보완 시나리오")).append("\n");
        sb.append("- **우선순위**: `")
          .append(s.getOrDefault("priority", "MEDIUM")).append("`\n");
        sb.append("- **테스트 시나리오**: ")
          .append(s.getOrDefault("testScenario", "")).append("\n");
        sb.append("- **기대 결과**: ")
          .append(s.getOrDefault("expectedResult", "")).append("\n");
        sb.append("\n");
    }
}
```

그리고 파일 상단 import에 `import java.util.Collections;`가 없으면 추가한다.

- [ ] **Step 2: Phase9Tests에 마크다운 렌더링 검증 테스트 추가**

```java
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

    // Preview를 통해 마크다운 내용 확인
    var preview = reportService.getReportPreview(response.getReportId());
    String content = preview.getContent();

    assertTrue(content.contains("보완 테스트 시나리오"), "보완 섹션 헤더가 포함되어야 함");
    assertTrue(content.contains("보완-2"), "보완-2 시나리오가 포함되어야 함");
    assertTrue(content.contains("보완-3"), "보완-3 시나리오가 포함되어야 함");

    reportService.deleteReport(response.getReportId());
}
```

- [ ] **Step 3: 테스트 실행**

```bash
cd backend && ./mvnw test -Dtest=Phase9Tests 2>&1 | tail -20
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 4: 전체 테스트 회귀 확인**

```bash
cd backend && ./mvnw test 2>&1 | tail -15
```

Expected: BUILD SUCCESS, 0 failures

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java \
        backend/src/test/java/com/yeonam/tester/Phase9Tests.java
git commit -m "feat: ReportRenderEngine에 보완 시나리오 섹션 렌더링 추가"
```

---

## Task 9: 프론트엔드 — api.ts 타입 + AnalysisResultPage UI

**Files:**
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/pages/AnalysisResultPage.tsx`

- [ ] **Step 1: api.ts — reportApi.generate() 시그니처 업데이트**

`frontend/src/services/api.ts`에서 `reportApi.generate` 라인을 찾아 다음으로 교체:

```typescript
generate: (analysisId: string, format: 'MARKDOWN' | 'PDF', testCaseIds?: string[], targetScenarioCount?: number) =>
  api.post<any>(`/analysis/${analysisId}/reports`, { reportFormat: format, testCaseIds, targetScenarioCount }),
```

- [ ] **Step 2: AnalysisResultPage.tsx — targetScenarioCount 상태 추가**

파일에서 `const [reportFormat, setReportFormat]` 라인을 찾고, 그 다음 줄에 추가:

```typescript
const [targetScenarioCount, setTargetScenarioCount] = useState<number>(0);
```

- [ ] **Step 3: AnalysisResultPage.tsx — testCases 로드 후 기본값 동기화**

`testCases` 상태가 설정되는 useEffect 혹은 fetch 완료 지점을 찾아, testCases 설정 직후에 추가:

```typescript
setTargetScenarioCount(fetchedTestCases.length); // 기본값 = 현재 TC 수
```

(변수명은 실제 코드의 응답 변수명으로 맞출 것)

- [ ] **Step 4: AnalysisResultPage.tsx — 보고서 생성 API 호출 업데이트**

`reportApi.generate(analysisId, reportFormat, selectedTestCaseIds)` 라인을 다음으로 교체:

```typescript
const response = await reportApi.generate(analysisId, reportFormat, selectedTestCaseIds, targetScenarioCount);
```

- [ ] **Step 5: AnalysisResultPage.tsx — 시나리오 수 입력 UI 삽입**

보고서 포맷 선택 라디오 버튼 섹션(`보고서 산출 포맷 선택`) 바로 아래에 다음 JSX를 삽입:

```tsx
{/* 시나리오 수 선택 */}
<div className="mb-4">
  <div className="flex items-center gap-2 mb-2">
    <span className="material-symbols-outlined text-indigo-400">format_list_numbered</span>
    <span className="text-sm font-semibold text-gray-300">시나리오 수 선택 (최대 10개)</span>
  </div>
  <div className="flex items-center gap-3">
    <input
      type="range"
      min={testCases.length > 0 ? testCases.length : 1}
      max={10}
      value={targetScenarioCount}
      onChange={(e) => setTargetScenarioCount(Number(e.target.value))}
      className="flex-1 accent-indigo-500"
    />
    <span className="text-indigo-300 font-bold w-8 text-center">{targetScenarioCount}</span>
  </div>
  <p className="text-xs text-gray-500 mt-1">
    현재 분석된 테스트 케이스 {testCases.length}개 + 보완 시나리오 {Math.max(0, targetScenarioCount - testCases.length)}개
  </p>
</div>
```

- [ ] **Step 6: TypeScript 타입 체크**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: 오류 없음

- [ ] **Step 7: Commit**

```bash
git add frontend/src/services/api.ts \
        frontend/src/pages/AnalysisResultPage.tsx
git commit -m "feat: 보고서 생성 UI에 시나리오 수 슬라이더 및 API targetScenarioCount 파라미터 추가"
```

---

## 완료 후 최종 검증

- [ ] **전체 백엔드 테스트 실행**

```bash
cd backend && ./mvnw test 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures, Phase9Tests 포함

- [ ] **전체 프론트엔드 타입 체크**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 오류 없음

- [ ] **수동 동작 확인 체크리스트**
  - [ ] 보고서 생성 UI에서 슬라이더로 시나리오 수 선택 가능
  - [ ] `targetScenarioCount=0` → 보완 시나리오 없이 보고서 생성
  - [ ] `targetScenarioCount > 현재 TC 수` → 보고서에 "보완 테스트 시나리오" 섹션 출력
  - [ ] `targetScenarioCount=11` → API가 400 에러 반환
  - [ ] 기존 Phase6Tests 회귀 없음
