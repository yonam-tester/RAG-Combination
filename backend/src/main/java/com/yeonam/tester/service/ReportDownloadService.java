package com.yeonam.tester.service;

import com.yeonam.tester.domain.Report;
import com.yeonam.tester.repository.ReportRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class ReportDownloadService {

    private final ReportRepository reportRepository;
    private final ReportAssemblyService assemblyService;
    private final ReportRenderEngine renderEngine;
    private final S3Client s3Client;

    @Value("${aws.s3.buckets.reports}")
    private String reportsBucket;

    public ReportDownloadService(ReportRepository reportRepository,
                                 ReportAssemblyService assemblyService,
                                 ReportRenderEngine renderEngine,
                                 S3Client s3Client) {
        this.reportRepository = reportRepository;
        this.assemblyService = assemblyService;
        this.renderEngine = renderEngine;
        this.s3Client = s3Client;
    }

    /**
     * Downloads a report's byte content, regenerating the file on-the-fly if it has been lost in S3.
     */
    @Transactional
    public byte[] downloadReportBytes(String reportId) {
        return downloadReportBytes(reportId, "MARKDOWN");
    }

    @Transactional
    public byte[] downloadReportBytes(String reportId, String format) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        String targetFormat = format != null ? format.toUpperCase() : "MARKDOWN";
        if (!"MARKDOWN".equals(targetFormat) && !"PDF".equals(targetFormat)) {
            targetFormat = "MARKDOWN";
        }

        byte[] mdBytes = null;
        boolean fileExists = true;
        try {
            // Check if file exists in S3
            HeadObjectRequest headOb = HeadObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(report.getS3Path())
                    .build();
            s3Client.headObject(headOb);
        } catch (NoSuchKeyException e) {
            fileExists = false;
        } catch (Exception e) {
            fileExists = false;
        }

        if (fileExists) {
            try {
                GetObjectRequest getOb = GetObjectRequest.builder()
                        .bucket(reportsBucket)
                        .key(report.getS3Path())
                        .build();
                ResponseBytes<?> responseBytes = s3Client.getObjectAsBytes(getOb);
                mdBytes = responseBytes.asByteArray();
            } catch (Exception e) {
                System.err.println("Failed to read report from S3: " + e.getMessage() + ". Regenerating as fallback...");
                fileExists = false;
            }
        }

        String markdown;
        if (!fileExists || mdBytes == null) {
            System.out.println("File lost in S3, auto-regenerating report: " + reportId);
            String analysisId = report.getAnalysisJob().getAnalysisId();
            java.util.List<String> tcIds = report.getReportTestCases().stream()
                    .map(rtc -> rtc.getTestCase().getTestCaseId())
                    .collect(java.util.stream.Collectors.toList());

            Map<String, Object> data = assemblyService.assembleReportData(analysisId, tcIds);
            markdown = renderEngine.renderMarkdown(data);

            // Upload regenerated markdown file back to S3
            try {
                byte[] uploadBytes = markdown.getBytes(StandardCharsets.UTF_8);
                PutObjectRequest putOb = PutObjectRequest.builder()
                        .bucket(reportsBucket)
                        .key(report.getS3Path())
                        .contentType("text/markdown")
                        .build();
                s3Client.putObject(putOb, RequestBody.fromBytes(uploadBytes));
            } catch (Exception e) {
                System.err.println("Failed to upload regenerated report: " + e.getMessage());
            }
        } else {
            markdown = new String(mdBytes, StandardCharsets.UTF_8);
        }

        // On-the-fly rendering based on requested format
        if ("PDF".equals(targetFormat)) {
            return renderEngine.renderPdf(markdown);
        } else {
            return markdown.getBytes(StandardCharsets.UTF_8);
        }
    }
}
