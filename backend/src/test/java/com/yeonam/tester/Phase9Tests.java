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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Phase9Tests {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
}
