package com.yeonam.tester.controller;

import com.yeonam.tester.dto.ReportCreateRequest;
import com.yeonam.tester.dto.ReportListResponse;
import com.yeonam.tester.dto.ReportPreviewResponse;
import com.yeonam.tester.dto.ReportResponse;
import com.yeonam.tester.service.ReportDownloadService;
import com.yeonam.tester.service.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*")
public class ReportController {

    private final ReportService reportService;
    private final ReportDownloadService reportDownloadService;

    public ReportController(ReportService reportService, ReportDownloadService reportDownloadService) {
        this.reportService = reportService;
        this.reportDownloadService = reportDownloadService;
    }

    @PostMapping("/api/analysis/{analysisId}/reports")
    public ResponseEntity<?> generateReport(@PathVariable String analysisId, @RequestBody ReportCreateRequest request) {
        try {
            ReportResponse response = reportService.generateReport(analysisId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Failed to generate report: " + e.getMessage()));
        }
    }

    @GetMapping("/api/projects/{projectId}/reports")
    public ResponseEntity<ReportListResponse> getReportsByProject(
            @PathVariable String projectId,
            @RequestParam(required = false) String fileId,
            @RequestParam(required = false) String analysisId) {
        ReportListResponse response = reportService.getReportsByProject(projectId, fileId, analysisId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/reports/{reportId}")
    public ResponseEntity<?> getReportPreview(@PathVariable String reportId) {
        try {
            ReportPreviewResponse preview = reportService.getReportPreview(reportId);
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Failed to load report preview: " + e.getMessage()));
        }
    }

    @GetMapping("/api/reports/{reportId}/download")
    public ResponseEntity<?> downloadReport(@PathVariable String reportId, @RequestParam(required = false) String format) {
        try {
            String targetFormat = format != null ? format.toUpperCase() : "MARKDOWN";
            if (!"MARKDOWN".equals(targetFormat) && !"PDF".equals(targetFormat)) {
                targetFormat = "MARKDOWN";
            }

            byte[] fileBytes = reportDownloadService.downloadReportBytes(reportId, targetFormat);

            String extension = "PDF".equals(targetFormat) ? "pdf" : "md";
            String contentType = "PDF".equals(targetFormat) ? "application/pdf" : "text/markdown";
            String fileName = String.format("QA_Report_%s.%s", reportId, extension);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(fileBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Download failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/reports/{reportId}")
    public ResponseEntity<?> deleteReport(@PathVariable String reportId) {
        try {
            reportService.deleteReport(reportId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Delete failed: " + e.getMessage()));
        }
    }

    private static class ErrorResponse {
        private final String message;
        public ErrorResponse(String message) {
            this.message = message;
        }
        public String getMessage() {
            return message;
        }
    }
}
