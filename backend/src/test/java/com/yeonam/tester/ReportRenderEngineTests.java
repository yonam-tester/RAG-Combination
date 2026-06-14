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
