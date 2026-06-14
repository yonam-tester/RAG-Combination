# QA 보고서 템플릿 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ReportRenderEngine.renderMarkdown()`을 재작성해 RAG Evidence 덤프 제거, 위험 태그 문장 변환, QA 용어 안내 섹션 추가, 들여쓰기 통일을 적용한다.

**Architecture:** `renderMarkdown()` 내부에 3개의 private 헬퍼 메서드를 추가한다: `buildRiskSentences()`(태그→문장 변환), `formatSourceLabel()`(소스명→레이블), `deduplicateSources()`(중복 소스 제거). 도메인 모델(Evidence, RiskItem 등)은 변경하지 않는다.

**Tech Stack:** Java 17, Spring Boot, JUnit 5 (Spring context 없이 순수 단위 테스트)

---

## 파일 구조

| 파일 | 역할 |
|------|------|
| `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java` | `renderMarkdown()` 재작성 + 3개 private 헬퍼 추가 |
| `backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java` | 순수 단위 테스트 (Spring context 불필요) |

---

## Task 1: 테스트 클래스 생성 + Section 0 (용어 안내) 구현

**Files:**
- Create: `backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java`
- Modify: `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java`

- [ ] **Step 1: 테스트 클래스 생성 + 실패 테스트 작성**

`backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java` 를 새로 생성한다:

```java
package com.yeonam.tester;

import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.Project;
import com.yeonam.tester.service.ReportRenderEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportRenderEngineTests {

    private ReportRenderEngine engine;
    private Map<String, Object> baseModel;

    @BeforeEach
    void setUp() {
        engine = new ReportRenderEngine();

        Project project = Project.builder()
                .projectId("PRJ-TEST")
                .name("테스트 프로젝트")
                .githubUrl("https://github.com/test/repo")
                .githubBranch("main")
                .createdAt(LocalDateTime.now())
                .build();

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-TEST")
                .project(project)
                .status("COMPLETED")
                .summary("RAG 기반 분석 완료. 추출된 요구사항 수: 5, 생성된 테스트 케이스 수: 10.")
                .qaPerspective("API,BACKEND")
                .build();

        baseModel = new HashMap<>();
        baseModel.put("project", project);
        baseModel.put("job", job);
        baseModel.put("requirements", Collections.emptyList());
        baseModel.put("testCases", Collections.emptyList());
        baseModel.put("additionalScenarios", Collections.emptyList());
    }

    // --- Task 1: Section 0 용어 안내 ---

    @Test
    void renderMarkdown_containsGlossarySection() {
        String output = engine.renderMarkdown(baseModel);
        assertTrue(output.contains("## 0. 이 보고서를 읽는 방법"),
                "Section 0 헤더가 없습니다");
        assertTrue(output.contains("테스트 케이스 (TC)"),
                "TC 용어 설명이 없습니다");
        assertTrue(output.contains("사전 조건"),
                "사전 조건 용어 설명이 없습니다");
        assertTrue(output.contains("근거 출처"),
                "근거 출처 용어 설명이 없습니다");
    }

    @Test
    void renderMarkdown_glossarySectionAppearsBeforeSection1() {
        String output = engine.renderMarkdown(baseModel);
        int section0Idx = output.indexOf("## 0. 이 보고서를 읽는 방법");
        int section1Idx = output.indexOf("## 1. 분석 대상 개요");
        assertTrue(section0Idx < section1Idx,
                "Section 0이 Section 1보다 앞에 있어야 합니다");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=ReportRenderEngineTests#renderMarkdown_containsGlossarySection+renderMarkdown_glossarySectionAppearsBeforeSection1 -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20
```

Expected: `FAIL` — `Section 0 헤더가 없습니다`

- [ ] **Step 3: `renderMarkdown()` 앞에 Section 0 블록 추가**

`ReportRenderEngine.java`의 `renderMarkdown()` 메서드에서 `sb.append("# 📌...")` 직후에 삽입한다:

```java
// Section 0: Glossary
sb.append("## 0. 이 보고서를 읽는 방법\n\n");
sb.append("이 보고서는 AI가 프로젝트 요구사항 문서를 분석하여 자동 생성한 QA 검증 보고서입니다.\n");
sb.append("테스트 및 QA 경험이 없어도 아래 용어 설명을 참고하면 내용을 이해할 수 있습니다.\n\n");
sb.append("| 용어 | 설명 |\n");
sb.append("|------|------|\n");
sb.append("| 테스트 케이스 (TC) | 특정 기능이 올바르게 동작하는지 확인하기 위한 하나의 검증 시나리오 |\n");
sb.append("| 우선순위 | HIGH: 반드시 검증 / MEDIUM: 가능하면 검증 / LOW: 여유 시 검증 |\n");
sb.append("| 신뢰도 | AI가 이 테스트 케이스를 얼마나 확신하는지의 정도 (HIGH/MEDIUM/LOW) |\n");
sb.append("| 사전 조건 | 테스트를 시작하기 전에 반드시 갖춰야 할 환경이나 상태 |\n");
sb.append("| 테스트 절차 | 테스트를 수행하는 순서대로 나열한 단계별 행동 목록 |\n");
sb.append("| 기대 결과 | 테스트가 통과할 때 나타나야 하는 정상적인 결과 |\n");
sb.append("| 위험 요인 | 이 기능에서 발생할 수 있는 잠재적인 문제나 취약점 |\n");
sb.append("| 근거 출처 | AI가 이 테스트 케이스를 생성할 때 참고한 문서 |\n");
sb.append("| 보완 시나리오 | RAG 분석 외에 LLM이 추가로 제안한 테스트 시나리오 |\n\n");
```

또한 기존 Section 1 헤더 앞에 `## 1.` 번호를 붙인다:

```java
// 기존: sb.append("## 1. 분석 대상 개요\n");
// 이미 올바른 형식이므로 그대로 유지
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=ReportRenderEngineTests#renderMarkdown_containsGlossarySection+renderMarkdown_glossarySectionAppearsBeforeSection1 -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: 커밋**

```bash
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination add backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination commit -m "feat: QA 보고서 Section 0 용어 안내 블록 추가"
```

---

## Task 2: 위험 요인 태그 → 문장 변환 (`buildRiskSentences`)

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java`
- Modify: `backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java`

- [ ] **Step 1: 실패 테스트 추가**

`ReportRenderEngineTests.java`에 다음 테스트를 추가한다:

```java
// --- Task 2: 위험 요인 문장 변환 ---

@Test
void renderMarkdown_riskTagsConvertedToKoreanSentence() {
    com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
            .testCaseId("TC-001")
            .build();

    com.yeonam.tester.domain.RiskItem risk1 = com.yeonam.tester.domain.RiskItem.builder()
            .riskId("R-001").testCase(tc).riskType("권한_부여").build();
    com.yeonam.tester.domain.RiskItem risk2 = com.yeonam.tester.domain.RiskItem.builder()
            .riskId("R-002").testCase(tc).riskType("입력검증").build();

    Map<String, Object> tcMap = new HashMap<>();
    tcMap.put("testCaseId", "TC-001");
    tcMap.put("testCaseName", "테스트");
    tcMap.put("priority", "HIGH");
    tcMap.put("confidenceLevel", "HIGH");
    tcMap.put("requirementId", "REQ-001");
    tcMap.put("requirementText", "테스트 요구사항");
    tcMap.put("testScenario", "시나리오");
    tcMap.put("precondition", "");
    tcMap.put("testSteps", List.of("1. 단계 1"));
    tcMap.put("expectedResult", "성공");
    tcMap.put("risks", List.of(risk1, risk2));
    tcMap.put("evidences", Collections.emptyList());

    baseModel.put("testCases", List.of(tcMap));
    String output = engine.renderMarkdown(baseModel);

    assertTrue(output.contains("접근 권한이 없는 사용자의 요청이 차단되는지 검증이 필요합니다"),
            "권한 관련 위험 문장이 없습니다");
    assertTrue(output.contains("잘못된 입력값에 대해 서버가 적절히 거부하는지 확인이 필요합니다"),
            "입력검증 관련 위험 문장이 없습니다");
    assertFalse(output.contains("##권한_부여"),
            "원본 해시태그 형식이 남아 있습니다");
}

@Test
void renderMarkdown_noMatchingRiskTags_riskSectionOmitted() {
    com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
            .testCaseId("TC-002")
            .build();
    com.yeonam.tester.domain.RiskItem unknownRisk = com.yeonam.tester.domain.RiskItem.builder()
            .riskId("R-003").testCase(tc).riskType("알수없는태그").build();

    Map<String, Object> tcMap = new HashMap<>();
    tcMap.put("testCaseId", "TC-002");
    tcMap.put("testCaseName", "테스트2");
    tcMap.put("priority", "MEDIUM");
    tcMap.put("confidenceLevel", "MEDIUM");
    tcMap.put("requirementId", "REQ-002");
    tcMap.put("requirementText", "요구사항2");
    tcMap.put("testScenario", "시나리오2");
    tcMap.put("precondition", "");
    tcMap.put("testSteps", List.of("1. 단계"));
    tcMap.put("expectedResult", "결과");
    tcMap.put("risks", List.of(unknownRisk));
    tcMap.put("evidences", Collections.emptyList());

    baseModel.put("testCases", List.of(tcMap));
    String output = engine.renderMarkdown(baseModel);

    assertFalse(output.contains("**위험 요인**"),
            "매칭 태그가 없을 때 위험 요인 섹션이 출력되면 안 됩니다");
}

@Test
void renderMarkdown_duplicateRiskTags_sentenceAppearsOnce() {
    com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
            .testCaseId("TC-003")
            .build();
    // 같은 그룹의 태그 두 개
    com.yeonam.tester.domain.RiskItem r1 = com.yeonam.tester.domain.RiskItem.builder()
            .riskId("R-004").testCase(tc).riskType("오류처리").build();
    com.yeonam.tester.domain.RiskItem r2 = com.yeonam.tester.domain.RiskItem.builder()
            .riskId("R-005").testCase(tc).riskType("예외처리").build();

    Map<String, Object> tcMap = new HashMap<>();
    tcMap.put("testCaseId", "TC-003");
    tcMap.put("testCaseName", "테스트3");
    tcMap.put("priority", "HIGH");
    tcMap.put("confidenceLevel", "HIGH");
    tcMap.put("requirementId", "REQ-003");
    tcMap.put("requirementText", "요구사항3");
    tcMap.put("testScenario", "시나리오3");
    tcMap.put("precondition", "");
    tcMap.put("testSteps", List.of("1. 단계"));
    tcMap.put("expectedResult", "결과");
    tcMap.put("risks", List.of(r1, r2));
    tcMap.put("evidences", Collections.emptyList());

    baseModel.put("testCases", List.of(tcMap));
    String output = engine.renderMarkdown(baseModel);

    String sentence = "예외 상황 발생 시 적절한 오류 응답이 반환되어야 합니다";
    int firstIdx = output.indexOf(sentence);
    int lastIdx = output.lastIndexOf(sentence);
    assertEquals(firstIdx, lastIdx, "동일한 위험 문장이 중복 출력됩니다");
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=ReportRenderEngineTests#renderMarkdown_riskTagsConvertedToKoreanSentence+renderMarkdown_noMatchingRiskTags_riskSectionOmitted+renderMarkdown_duplicateRiskTags_sentenceAppearsOnce -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20
```

Expected: `FAIL` — 위험 문장 없음

- [ ] **Step 3: `buildRiskSentences()` 구현 + `renderMarkdown()` 연결**

`ReportRenderEngine.java` 클래스 하단에 다음 메서드를 추가한다. 기존 import 목록에 `java.util.LinkedHashSet`을 추가한다:

```java
private static final Map<Set<String>, String> RISK_SENTENCE_MAP;

static {
    // 순서 보장 LinkedHashMap 사용
    RISK_SENTENCE_MAP = new java.util.LinkedHashMap<>();
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "권한_부여", "권한검증", "관리자", "AUTHORIZATION_BYPASS", "PRIVILEGE_ESCALATION"
    )), "접근 권한이 없는 사용자의 요청이 차단되는지 검증이 필요합니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "입력검증", "유효성검사", "입력오류", "데이터유효성"
    )), "잘못된 입력값에 대해 서버가 적절히 거부하는지 확인이 필요합니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "오류처리", "예외처리", "서버오류", "오류"
    )), "예외 상황 발생 시 적절한 오류 응답이 반환되어야 합니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "로그인", "전화번호", "인증"
    )), "인증 정보의 정확성과 보안 처리 여부를 확인해야 합니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "출석", "위치검증", "GPS", "위치오류"
    )), "위치 데이터의 정확성과 출석 처리 로직의 신뢰성을 검증해야 합니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "엑셀업로드", "파일다운로드"
    )), "파일 입출력 처리 중 데이터 손실이나 오류가 없는지 확인이 필요합니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "TEST_COUPLING", "TEST_STATE_POLLUTION"
    )), "테스트 간 상태 공유로 인한 결과 오염 가능성이 있습니다.");
    RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
        "CREDENTIAL_EXPOSURE"
    )), "민감 정보(비밀번호, 토큰)가 코드나 로그에 노출될 위험이 있습니다.");
}

private List<String> buildRiskSentences(List<?> risks) {
    if (risks == null || risks.isEmpty()) return Collections.emptyList();

    Set<String> tags = new java.util.HashSet<>();
    for (Object r : risks) {
        String tag = (r instanceof RiskItem) ? ((RiskItem) r).getRiskType() : r.toString();
        tags.add(tag);
    }

    Set<String> added = new java.util.LinkedHashSet<>();
    for (Map.Entry<Set<String>, String> entry : RISK_SENTENCE_MAP.entrySet()) {
        for (String tag : tags) {
            if (entry.getKey().contains(tag)) {
                added.add(entry.getValue());
                break;
            }
        }
    }
    return new java.util.ArrayList<>(added);
}
```

`renderMarkdown()` 의 기존 위험 요소 태그 출력 부분을 교체한다.

**기존 코드 (제거):**
```java
List<?> risks = (List<?>) tc.get("risks");
if (risks != null && !risks.isEmpty()) {
    sb.append("- **위험 요소 태그**: ");
    for (int i = 0; i < risks.size(); i++) {
        Object r = risks.get(i);
        String rType = (r instanceof com.yeonam.tester.domain.RiskItem) ? ((com.yeonam.tester.domain.RiskItem) r).getRiskType() : r.toString();
        sb.append("#").append(rType).append(" ");
    }
    sb.append("\n");
}
```

**새 코드 (대체):**
```java
List<?> risks = (List<?>) tc.get("risks");
List<String> riskSentences = buildRiskSentences(risks);
if (!riskSentences.isEmpty()) {
    sb.append("- **위험 요인**:\n");
    for (String sentence : riskSentences) {
        sb.append("  - ").append(sentence).append("\n");
    }
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=ReportRenderEngineTests#renderMarkdown_riskTagsConvertedToKoreanSentence+renderMarkdown_noMatchingRiskTags_riskSectionOmitted+renderMarkdown_duplicateRiskTags_sentenceAppearsOnce -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: 커밋**

```bash
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination add backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination commit -m "feat: 위험 요소 태그를 한국어 문장으로 변환하는 buildRiskSentences 추가"
```

---

## Task 3: Evidence 소스 레이블 변환 + 중복 제거 (`formatSourceLabel`, `deduplicateSources`)

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java`
- Modify: `backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java`

- [ ] **Step 1: 실패 테스트 추가**

`ReportRenderEngineTests.java`에 추가한다:

```java
// --- Task 3: Evidence 소스 레이블 + 중복 제거 ---

private Map<String, Object> makeTcMapWithEvidences(List<com.yeonam.tester.domain.Evidence> evidences) {
    Map<String, Object> tcMap = new HashMap<>();
    tcMap.put("testCaseId", "TC-E01");
    tcMap.put("testCaseName", "Evidence 테스트");
    tcMap.put("priority", "HIGH");
    tcMap.put("confidenceLevel", "HIGH");
    tcMap.put("requirementId", "REQ-E01");
    tcMap.put("requirementText", "요구사항");
    tcMap.put("testScenario", "시나리오");
    tcMap.put("precondition", "");
    tcMap.put("testSteps", List.of("1. 단계"));
    tcMap.put("expectedResult", "결과");
    tcMap.put("risks", Collections.emptyList());
    tcMap.put("evidences", evidences);
    return tcMap;
}

@Test
void renderMarkdown_evidenceTextNotIncluded() {
    com.yeonam.tester.domain.Evidence ev = com.yeonam.tester.domain.Evidence.builder()
            .evidenceId("EV-001")
            .sourceName("DOC-18BD6CAB_SRS.md")
            .sourceSection("## 소프트웨어 요구사항 명세서")
            .evidenceText("이것은 절대 보고서에 출력되면 안 되는 원문 텍스트입니다.")
            .score(0.9)
            .build();

    baseModel.put("testCases", List.of(makeTcMapWithEvidences(List.of(ev))));
    String output = engine.renderMarkdown(baseModel);

    assertFalse(output.contains("이것은 절대 보고서에 출력되면 안 되는 원문 텍스트입니다."),
            "evidenceText 원문이 보고서에 출력되면 안 됩니다");
}

@Test
void renderMarkdown_srsSourceNameConvertsToLabel() {
    com.yeonam.tester.domain.Evidence ev = com.yeonam.tester.domain.Evidence.builder()
            .evidenceId("EV-002")
            .sourceName("DOC-18BD6CAB_SRS.md")
            .sourceSection("SRS")
            .evidenceText("원문")
            .score(0.9)
            .build();

    baseModel.put("testCases", List.of(makeTcMapWithEvidences(List.of(ev))));
    String output = engine.renderMarkdown(baseModel);

    assertTrue(output.contains("소프트웨어 요구사항 명세서"),
            "SRS 파일명이 레이블로 변환되어야 합니다");
    assertTrue(output.contains("**근거 출처**"),
            "근거 출처 헤더가 없습니다");
}

@Test
void renderMarkdown_owaspSourceNameConvertsToLabel() {
    com.yeonam.tester.domain.Evidence ev = com.yeonam.tester.domain.Evidence.builder()
            .evidenceId("EV-003")
            .sourceName("owasp_security_knowledge_cards.json")
            .sourceSection("security")
            .evidenceText("원문")
            .score(0.8)
            .build();

    baseModel.put("testCases", List.of(makeTcMapWithEvidences(List.of(ev))));
    String output = engine.renderMarkdown(baseModel);

    assertTrue(output.contains("보안 검증 가이드 (OWASP)"),
            "OWASP 파일명이 레이블로 변환되어야 합니다");
}

@Test
void renderMarkdown_duplicateSourceNamesDeduped() {
    com.yeonam.tester.domain.Evidence ev1 = com.yeonam.tester.domain.Evidence.builder()
            .evidenceId("EV-004").sourceName("DOC-18BD6CAB_SRS.md")
            .sourceSection("SRS").evidenceText("원문1").score(0.9).build();
    com.yeonam.tester.domain.Evidence ev2 = com.yeonam.tester.domain.Evidence.builder()
            .evidenceId("EV-005").sourceName("DOC-18BD6CAB_SRS.md")
            .sourceSection("SRS").evidenceText("원문2").score(0.8).build();

    baseModel.put("testCases", List.of(makeTcMapWithEvidences(List.of(ev1, ev2))));
    String output = engine.renderMarkdown(baseModel);

    String label = "소프트웨어 요구사항 명세서";
    int first = output.indexOf(label);
    int last = output.lastIndexOf(label);
    assertEquals(first, last, "동일 소스가 중복 출력되면 안 됩니다");
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest="ReportRenderEngineTests#renderMarkdown_evidenceTextNotIncluded+renderMarkdown_srsSourceNameConvertsToLabel+renderMarkdown_owaspSourceNameConvertsToLabel+renderMarkdown_duplicateSourceNamesDeduped" -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20
```

Expected: `FAIL`

- [ ] **Step 3: `formatSourceLabel()` + `deduplicateSources()` 구현**

`ReportRenderEngine.java` 하단에 추가한다:

```java
private String formatSourceLabel(String sourceName) {
    if (sourceName == null) return "알 수 없는 출처";
    if (sourceName.endsWith("_SRS.md") || sourceName.contains("SRS")) return "소프트웨어 요구사항 명세서";
    if (sourceName.endsWith("_SDD.md") || sourceName.contains("SDD")) return "소프트웨어 설계 문서";
    if (sourceName.startsWith("owasp_")) return "보안 검증 가이드 (OWASP)";
    if (sourceName.startsWith("istqb_")) return "테스트 기법 가이드 (ISTQB)";
    if (sourceName.startsWith("cypress_")) return "E2E 테스트 모범 사례 (Cypress)";
    if (sourceName.startsWith("playwright_")) return "E2E 테스트 기법 (Playwright)";
    if (sourceName.startsWith("nist_")) return "테스트 프로세스 가이드 (NIST/SAMATE)";
    return sourceName;
}

private List<String> deduplicateSources(List<?> evidences) {
    if (evidences == null || evidences.isEmpty()) return Collections.emptyList();
    Set<String> seen = new java.util.LinkedHashSet<>();
    for (Object evObj : evidences) {
        if (evObj instanceof Evidence) {
            String label = formatSourceLabel(((Evidence) evObj).getSourceName());
            seen.add(label);
        }
    }
    return new java.util.ArrayList<>(seen);
}
```

`renderMarkdown()`의 기존 Evidence 출력 부분을 교체한다.

**기존 코드 (제거):**
```java
List<?> evidences = (List<?>) tc.get("evidences");
if (evidences != null && !evidences.isEmpty()) {
    sb.append("- **매핑된 RAG 근거 문서 조각**:\n");
    for (Object evObj : evidences) {
        if (evObj instanceof com.yeonam.tester.domain.Evidence) {
            com.yeonam.tester.domain.Evidence ev = (com.yeonam.tester.domain.Evidence) evObj;
            sb.append("  > [**").append(ev.getSourceName()).append("** (").append(ev.getSourceSection() != null ? ev.getSourceSection() : "전체").append(")] ")
                    .append(ev.getEvidenceText()).append("\n");
        }
    }
}
```

**새 코드 (대체):**
```java
List<?> evidences = (List<?>) tc.get("evidences");
List<String> sourceLabels = deduplicateSources(evidences);
if (!sourceLabels.isEmpty()) {
    sb.append("- **근거 출처**:\n");
    for (String label : sourceLabels) {
        sb.append("  - ").append(label).append("\n");
    }
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest="ReportRenderEngineTests#renderMarkdown_evidenceTextNotIncluded+renderMarkdown_srsSourceNameConvertsToLabel+renderMarkdown_owaspSourceNameConvertsToLabel+renderMarkdown_duplicateSourceNamesDeduped" -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: 커밋**

```bash
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination add backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination commit -m "feat: Evidence 소스 레이블 변환 및 중복 제거 구현"
```

---

## Task 4: TC 절차 번호 목록 + Section 2 요약 + 전체 구조 검증

**Files:**
- Modify: `backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java`
- Modify: `backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java`

- [ ] **Step 1: 실패 테스트 추가**

```java
// --- Task 4: TC 절차 번호 + 전체 구조 ---

@Test
void renderMarkdown_testStepsUseNumberedList() {
    Map<String, Object> tcMap = new HashMap<>();
    tcMap.put("testCaseId", "TC-S01");
    tcMap.put("testCaseName", "절차 번호 테스트");
    tcMap.put("priority", "HIGH");
    tcMap.put("confidenceLevel", "HIGH");
    tcMap.put("requirementId", "REQ-S01");
    tcMap.put("requirementText", "요구사항");
    tcMap.put("testScenario", "시나리오");
    tcMap.put("precondition", "로그인 상태");
    tcMap.put("testSteps", List.of("1. 첫 번째 단계", "2. 두 번째 단계", "3. 세 번째 단계"));
    tcMap.put("expectedResult", "성공");
    tcMap.put("risks", Collections.emptyList());
    tcMap.put("evidences", Collections.emptyList());

    baseModel.put("testCases", List.of(tcMap));
    String output = engine.renderMarkdown(baseModel);

    // "  1. 첫 번째 단계" 형식이어야 함 (2칸 들여쓰기 + 번호)
    assertTrue(output.contains("  1. 첫 번째 단계"), "절차가 번호 목록 형식이어야 합니다");
    assertTrue(output.contains("  2. 두 번째 단계"), "두 번째 절차가 번호 목록 형식이어야 합니다");
    // "  - 1. ..." 형식이 아니어야 함
    assertFalse(output.contains("  - 1."), "잘못된 들여쓰기 형식(- 1.)이 남아 있습니다");
}

@Test
void renderMarkdown_section2ContainsSummaryNotSrsDump() {
    String output = engine.renderMarkdown(baseModel);

    assertTrue(output.contains("## 2. QA 분석 요약"), "Section 2 헤더가 없습니다");
    assertTrue(output.contains("RAG 기반 분석 완료"), "요약 텍스트가 없습니다");
    // SRS 전문이 아닌 짧은 요약만 있어야 함
    assertFalse(output.contains("## 소프트웨어 요구사항 명세서"),
            "Section 2에 SRS 전문이 포함되면 안 됩니다");
}

@Test
void renderMarkdown_sectionOrderIsCorrect() {
    String output = engine.renderMarkdown(baseModel);

    int idx0 = output.indexOf("## 0. 이 보고서를 읽는 방법");
    int idx1 = output.indexOf("## 1. 분석 대상 개요");
    int idx2 = output.indexOf("## 2. QA 분석 요약");
    int idx3 = output.indexOf("## 3. 테스트 케이스 목록");
    int idxA = output.indexOf("## Appendix.");

    assertTrue(idx0 < idx1, "Section 0이 1보다 앞에 있어야 합니다");
    assertTrue(idx1 < idx2, "Section 1이 2보다 앞에 있어야 합니다");
    assertTrue(idx2 < idx3, "Section 2가 3보다 앞에 있어야 합니다");
    assertTrue(idx3 < idxA, "Section 3이 Appendix보다 앞에 있어야 합니다");
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest="ReportRenderEngineTests#renderMarkdown_testStepsUseNumberedList+renderMarkdown_section2ContainsSummaryNotSrsDump+renderMarkdown_sectionOrderIsCorrect" -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20
```

Expected: `FAIL`

- [ ] **Step 3: TC 절차 출력 방식 수정**

`renderMarkdown()` 의 테스트 절차 출력 부분을 수정한다.

**기존 코드 (제거):**
```java
sb.append("- **테스트 절차**:\n");
Object stepsObj = tc.get("testSteps");
if (stepsObj instanceof List) {
    List<String> steps = (List<String>) stepsObj;
    for (String step : steps) {
        sb.append("  - ").append(step).append("\n");
    }
} else if (stepsObj instanceof String) {
    String stepsStr = (String) stepsObj;
    String[] lines = stepsStr.split("\n");
    for (String line : lines) {
        if (!line.trim().isEmpty()) {
            sb.append("  - ").append(line.replace("- ", "").replace("* ", "").trim()).append("\n");
        }
    }
} else {
    sb.append("  - 절차가 등록되지 않았습니다.\n");
}
```

**새 코드 (대체):**
```java
sb.append("- **테스트 절차**:\n");
Object stepsObj = tc.get("testSteps");
if (stepsObj instanceof List) {
    List<String> steps = (List<String>) stepsObj;
    int stepNum = 1;
    for (String step : steps) {
        // 기존 "1. " 접두사 제거 후 새로 번호 부여
        String cleaned = step.replaceAll("^\\d+\\.\\s*", "").replaceAll("^[-*]\\s*", "").trim();
        sb.append("  ").append(stepNum++).append(". ").append(cleaned).append("\n");
    }
} else if (stepsObj instanceof String) {
    String stepsStr = (String) stepsObj;
    String[] lines = stepsStr.split("\n");
    int stepNum = 1;
    for (String line : lines) {
        if (!line.trim().isEmpty()) {
            String cleaned = line.replaceAll("^\\d+\\.\\s*", "").replaceAll("^[-*]\\s*", "").trim();
            sb.append("  ").append(stepNum++).append(". ").append(cleaned).append("\n");
        }
    }
} else {
    sb.append("  1. 절차가 등록되지 않았습니다.\n");
}
```

- [ ] **Step 4: Section 2 헤더 + `### 2.1` 제거**

`renderMarkdown()`에서 Section 2 출력 부분을 수정한다.

**기존 코드 (제거):**
```java
sb.append("## 2. 요구사항 및 분석 관점\n");
sb.append("- **QA 관점**: ").append(job.getQaPerspective() != null ? job.getQaPerspective() : "기본 관점").append("\n");
if (job.getCustomPrompt() != null && !job.getCustomPrompt().isBlank()) {
    sb.append("- **사용자 맞춤 프롬프트**: ").append(job.getCustomPrompt()).append("\n");
}
sb.append("\n### 2.1 요구사항 명세 요약\n");
sb.append(job.getSummary() != null ? job.getSummary() : "추출된 요구사항 요약 정보가 없습니다.").append("\n\n");
```

**새 코드 (대체):**
```java
sb.append("## 2. QA 분석 요약\n\n");
sb.append("- **QA 관점**: ").append(job.getQaPerspective() != null ? job.getQaPerspective() : "기본 관점").append("\n");
if (job.getCustomPrompt() != null && !job.getCustomPrompt().isBlank()) {
    sb.append("- **맞춤 분석 지침**: ").append(job.getCustomPrompt()).append("\n");
}
sb.append("- **분석 결과**: ").append(job.getSummary() != null ? job.getSummary() : "요약 정보가 없습니다.").append("\n\n");
```

Section 3 헤더도 업데이트한다:

**기존 코드:**
```java
sb.append("## 3. 테스트 케이스 생성 결과\n\n");
```

**새 코드:**
```java
sb.append("## 3. 테스트 케이스 목록\n\n");
```

- [ ] **Step 5: 테스트 재실행 — 통과 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest="ReportRenderEngineTests#renderMarkdown_testStepsUseNumberedList+renderMarkdown_section2ContainsSummaryNotSrsDump+renderMarkdown_sectionOrderIsCorrect" -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: 전체 테스트 스위트 실행**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn test -Dtest=ReportRenderEngineTests -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -15
```

Expected: 모든 테스트 `BUILD SUCCESS`

- [ ] **Step 7: 커밋**

```bash
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination add backend/src/main/java/com/yeonam/tester/service/ReportRenderEngine.java backend/src/test/java/com/yeonam/tester/ReportRenderEngineTests.java
git -C /Users/rinaeshin/IdeaProjects/RAG-Combination commit -m "feat: TC 절차 번호 목록 및 Section 2 요약 형식 개선"
```

---

## Self-Review

### 스펙 커버리지 확인

| 스펙 요구사항 | 커버하는 Task |
|--------------|--------------|
| Section 0 용어 안내 | Task 1 |
| 위험 태그 → 문장 변환 | Task 2 |
| 매칭 없을 때 위험 요인 섹션 생략 | Task 2 |
| 중복 문장 제거 | Task 2 |
| evidenceText 원문 제거 | Task 3 |
| 소스명 → 레이블 변환 (SRS, SDD, OWASP 등) | Task 3 |
| 중복 소스 제거 | Task 3 |
| 절차 번호 목록 (`  1. `) | Task 4 |
| Section 2 헤더 변경 + SRS 덤프 제거 | Task 4 |
| Section 3 헤더 변경 | Task 4 |
| 전체 섹션 순서 (0→1→2→3→Appendix) | Task 4 |

### Placeholder 없음 확인 ✓

모든 step에 실제 코드 포함. "TBD", "TODO" 없음.

### 타입 일관성 확인 ✓

- `buildRiskSentences(List<?>)` → `List<String>` — Task 2 정의, Task 2 사용
- `formatSourceLabel(String)` → `String` — Task 3 정의, Task 3 내부 사용
- `deduplicateSources(List<?>)` → `List<String>` — Task 3 정의, Task 3 사용
