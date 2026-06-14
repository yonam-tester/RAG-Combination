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
}
