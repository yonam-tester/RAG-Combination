package com.yeonam.tester;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.ReportCreateRequest;
import com.yeonam.tester.dto.ReportResponse;
import com.yeonam.tester.repository.*;
import com.yeonam.tester.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Phase6Tests {

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

    @Autowired
    private ReportDownloadService reportDownloadService;

    @Autowired
    private FileService fileService;

    @Test
    @Transactional
    void testReportAssemblyAndRender() {
        // 1. Setup metadata
        Project project = Project.builder()
                .projectId("PRJ-P6-RENDER")
                .name("Phase 6 Render Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        project = projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P6-RENDER")
                .project(project)
                .status("COMPLETED")
                .summary("Render test summary")
                .build();
        analysisJobRepository.saveAndFlush(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-P6-RENDER")
                .analysisJob(job)
                .requirementText("인증 정보 정합성 보장")
                .build();
        requirementRepository.saveAndFlush(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-P6-RENDER")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("로그인 예외")
                .testScenario("잘못된 값 차단")
                .expectedResult("에러 메시지")
                .priority("HIGH")
                .build();
        testCaseRepository.saveAndFlush(tc);

        // 2. Generate and Verify Report Render Output (Markdown)
        ReportResponse response = reportService.generateReport("ANL-P6-RENDER", ReportCreateRequest.builder().reportFormat("MARKDOWN").build());
        assertNotNull(response.getReportId());
        assertEquals("MARKDOWN", response.getReportFormat());

        Report report = reportRepository.findById(response.getReportId()).orElse(null);
        assertNotNull(report);

        // Clean up
        reportService.deleteReport(response.getReportId());
    }

    @Test
    @Transactional
    void testReportDownloadFallbackRegeneration() {
        // 1. Setup metadata
        Project project = Project.builder()
                .projectId("PRJ-P6-FALLBACK")
                .name("Phase 6 Fallback Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        project = projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P6-FALLBACK")
                .project(project)
                .status("COMPLETED")
                .summary("Fallback test summary")
                .build();
        analysisJobRepository.saveAndFlush(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-P6-FALLBACK")
                .analysisJob(job)
                .requirementText("결제 취소 복구 기능")
                .build();
        requirementRepository.saveAndFlush(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-P6-FALLBACK")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("결제 한도 초과")
                .testScenario("한도 부족 시도")
                .expectedResult("차단 팝업")
                .priority("HIGH")
                .build();
        testCaseRepository.saveAndFlush(tc);

        // 2. Generate report
        ReportResponse response = reportService.generateReport("ANL-P6-FALLBACK", ReportCreateRequest.builder().reportFormat("MARKDOWN").build());
        String reportId = response.getReportId();

        Report report = reportRepository.findById(reportId).orElseThrow();
        
        // 3. Delete physical file in S3 to mock S3 outage or lost scenario
        fileService.deleteFileFromS3(report.getS3Path());

        // 4. Download report - should silently regenerate it
        byte[] bytes = reportDownloadService.downloadReportBytes(reportId);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        
        String contentStr = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(contentStr.contains("Appendix. 보완 필요 사항 및 한계 고지"));
        assertTrue(contentStr.contains("본 시스템은 테스트 수행 결과를 보장하거나 최종 판단을 대체하지 않으며"));

        // Clean up
        reportService.deleteReport(reportId);
    }

    @Test
    @Transactional
    void testReportCompletePurge() {
        // 1. Setup metadata
        Project project = Project.builder()
                .projectId("PRJ-P6-PURGE")
                .name("Phase 6 Purge Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        project = projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P6-PURGE")
                .project(project)
                .status("COMPLETED")
                .summary("Purge test summary")
                .build();
        analysisJobRepository.saveAndFlush(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-P6-PURGE")
                .analysisJob(job)
                .requirementText("데이터 파기 기능")
                .build();
        requirementRepository.saveAndFlush(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-P6-PURGE")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("완전 파기 검증")
                .testScenario("삭제 API 호출")
                .expectedResult("물리 파일 완전 삭제")
                .priority("HIGH")
                .build();
        testCaseRepository.saveAndFlush(tc);

        // 2. Generate report
        ReportResponse response = reportService.generateReport("ANL-P6-PURGE", ReportCreateRequest.builder().reportFormat("MARKDOWN").build());
        String reportId = response.getReportId();

        Report report = reportRepository.findById(reportId).orElseThrow();
        String s3Path = report.getS3Path();

        // 3. Purge report
        reportService.deleteReport(reportId);

        // 4. Verify DB deletion
        assertFalse(reportRepository.existsById(reportId));
    }
}
