package com.yeonam.tester.dto;

import java.time.LocalDateTime;

public class ReportResponse {
    private String reportId;
    private String analysisId;
    private String reportFormat;
    private String status;
    private String downloadUrl;
    private LocalDateTime generatedAt;

    public ReportResponse() {}

    public ReportResponse(String reportId, String analysisId, String reportFormat, String status, String downloadUrl, LocalDateTime generatedAt) {
        this.reportId = reportId;
        this.analysisId = analysisId;
        this.reportFormat = reportFormat;
        this.status = status;
        this.downloadUrl = downloadUrl;
        this.generatedAt = generatedAt;
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

    public String getReportFormat() { return reportFormat; }
    public void setReportFormat(String reportFormat) { this.reportFormat = reportFormat; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public static ReportResponseBuilder builder() {
        return new ReportResponseBuilder();
    }

    public static class ReportResponseBuilder {
        private String reportId;
        private String analysisId;
        private String reportFormat;
        private String status;
        private String downloadUrl;
        private LocalDateTime generatedAt;

        public ReportResponseBuilder reportId(String reportId) {
            this.reportId = reportId;
            return this;
        }

        public ReportResponseBuilder analysisId(String analysisId) {
            this.analysisId = analysisId;
            return this;
        }

        public ReportResponseBuilder reportFormat(String reportFormat) {
            this.reportFormat = reportFormat;
            return this;
        }

        public ReportResponseBuilder status(String status) {
            this.status = status;
            return this;
        }

        public ReportResponseBuilder downloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
            return this;
        }

        public ReportResponseBuilder generatedAt(LocalDateTime generatedAt) {
            this.generatedAt = generatedAt;
            return this;
        }

        public ReportResponse build() {
            return new ReportResponse(reportId, analysisId, reportFormat, status, downloadUrl, generatedAt);
        }
    }
}
