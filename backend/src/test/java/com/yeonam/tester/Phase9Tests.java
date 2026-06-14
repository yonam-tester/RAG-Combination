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
