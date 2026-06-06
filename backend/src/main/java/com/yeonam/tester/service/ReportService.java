package com.yeonam.tester.service;

import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.Report;
import com.yeonam.tester.domain.ReportTestCase;
import com.yeonam.tester.dto.ReportCreateRequest;
import com.yeonam.tester.dto.ReportListResponse;
import com.yeonam.tester.dto.ReportPreviewResponse;
import com.yeonam.tester.dto.ReportResponse;
import com.yeonam.tester.repository.AnalysisJobRepository;
import com.yeonam.tester.repository.ReportRepository;
import com.yeonam.tester.repository.ReportTestCaseRepository;
import com.yeonam.tester.repository.TestCaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ReportAssemblyService assemblyService;
    private final ReportRenderEngine renderEngine;
    private final S3Client s3Client;
    private final ReportTestCaseRepository reportTestCaseRepository;
    private final TestCaseRepository testCaseRepository;

    @Value("${aws.s3.buckets.reports}")
    private String reportsBucket;

    public ReportService(ReportRepository reportRepository,
                         AnalysisJobRepository analysisJobRepository,
                         ReportAssemblyService assemblyService,
                         ReportRenderEngine renderEngine,
                         S3Client s3Client,
                         ReportTestCaseRepository reportTestCaseRepository,
                         TestCaseRepository testCaseRepository) {
        this.reportRepository = reportRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.assemblyService = assemblyService;
        this.renderEngine = renderEngine;
        this.s3Client = s3Client;
        this.reportTestCaseRepository = reportTestCaseRepository;
        this.testCaseRepository = testCaseRepository;
    }

    /**
     * Generates a QA validation report (Markdown or PDF), saves it to S3, and records its metadata in H2 DB.
     */
    @Transactional
    public ReportResponse generateReport(String analysisId, ReportCreateRequest request) {
        String format = request.getReportFormat() != null ? request.getReportFormat().toUpperCase() : "MARKDOWN";
        if (!"MARKDOWN".equals(format) && !"PDF".equals(format)) {
            throw new IllegalArgumentException("Unsupported report format. Must be MARKDOWN or PDF.");
        }

        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));

        String reportId = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // S3 cache single format: always standard markdown
        String extension = "md";
        String s3Path = String.format("reports/%s/%s.%s", analysisId, reportId, extension);

        // Assemble and Render content
        Map<String, Object> data = assemblyService.assembleReportData(analysisId, request.getTestCaseIds());
        String markdown = renderEngine.renderMarkdown(data);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        // Upload to S3 (reportsBucket)
        try {
            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Path)
                    .contentType("text/markdown")
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(bytes));
        } catch (Exception e) {
            throw new RuntimeException("S3 report upload failed: " + e.getMessage(), e);
        }

        // Record in DB
        Report report = Report.builder()
                .reportId(reportId)
                .analysisJob(job)
                .s3Path(s3Path)
                .format("MARKDOWN") // Standardize DB metadata to MARKDOWN
                .createdAt(LocalDateTime.now())
                .build();

        Report savedReport = reportRepository.save(report);

        // Save N:M mapping for selected test cases
        if (request.getTestCaseIds() != null && !request.getTestCaseIds().isEmpty()) {
            for (String tcId : request.getTestCaseIds()) {
                testCaseRepository.findById(tcId).ifPresent(tc -> {
                    ReportTestCase rtc = ReportTestCase.builder()
                            .report(savedReport)
                            .testCase(tc)
                            .build();
                    reportTestCaseRepository.save(rtc);
                    // Also populate the bidirection relationship to avoid lazy load issues in testing
                    savedReport.getReportTestCases().add(rtc);
                });
            }
        }

        return ReportResponse.builder()
                .reportId(savedReport.getReportId())
                .analysisId(analysisId)
                .reportFormat(savedReport.getFormat())
                .status("DONE")
                .downloadUrl(String.format("/api/reports/%s/download", savedReport.getReportId()))
                .generatedAt(savedReport.getCreatedAt())
                .build();
    }

    /**
     * Lists all reports for a specific project.
     */
    public ReportListResponse getReportsByProject(String projectId) {
        return getReportsByProject(projectId, null, null);
    }

    /**
     * Lists reports for a specific project, optionally filtered by fileId and/or analysisId.
     */
    public ReportListResponse getReportsByProject(String projectId, String fileId, String analysisId) {
        String filterFileId = (fileId != null && !fileId.trim().isEmpty()) ? fileId.trim() : null;
        String filterAnalysisId = (analysisId != null && !analysisId.trim().isEmpty()) ? analysisId.trim() : null;

        List<Report> reports = reportRepository.findByProjectWithFilters(projectId, filterFileId, filterAnalysisId);
        List<ReportListResponse.ReportItemDto> dtos = reports.stream()
                .map(r -> ReportListResponse.ReportItemDto.builder()
                        .reportId(r.getReportId())
                        .analysisId(r.getAnalysisJob().getAnalysisId())
                        .reportFormat(r.getFormat())
                        .status("DONE")
                        .generatedAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return ReportListResponse.builder().reports(dtos).build();
    }

    /**
     * Returns report content for previewing.
     */
    public ReportPreviewResponse getReportPreview(String reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        String content;
        if ("PDF".equals(report.getFormat())) {
            // For PDF, we can preview the markdown source compiled for this analysis
            Map<String, Object> data = assemblyService.assembleReportData(report.getAnalysisJob().getAnalysisId());
            content = renderEngine.renderMarkdown(data);
        } else {
            // Download markdown directly from S3
            try {
                GetObjectRequest getOb = GetObjectRequest.builder()
                        .bucket(reportsBucket)
                        .key(report.getS3Path())
                        .build();

                ResponseBytes<?> responseBytes = s3Client.getObjectAsBytes(getOb);
                content = responseBytes.asUtf8String();
            } catch (Exception e) {
                // If S3 file is missing, regenerate markdown preview dynamically as a fallback
                System.err.println("Preview: Report file lost in S3. Auto-regenerating preview...");
                Map<String, Object> data = assemblyService.assembleReportData(report.getAnalysisJob().getAnalysisId());
                content = renderEngine.renderMarkdown(data);
            }
        }

        return ReportPreviewResponse.builder()
                .reportId(report.getReportId())
                .reportFormat(report.getFormat())
                .content(content)
                .disclaimer("본 시스템은 테스트 수행 결과를 보장하거나 최종 판단을 대체하지 않으며, QA 담당자의 검토를 전제로 합니다.")
                .generatedAt(report.getCreatedAt())
                .build();
    }

    /**
     * Deletes report metadata from RDB and deletes report file from S3.
     */
    @Transactional
    public void deleteReport(String reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        // Delete from S3
        try {
            DeleteObjectRequest deleteOb = DeleteObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(report.getS3Path())
                    .build();
            s3Client.deleteObject(deleteOb);
        } catch (Exception e) {
            System.err.println("Failed to delete report physical file from S3: " + e.getMessage());
        }

        // Delete from DB
        reportRepository.delete(report);
    }
}
