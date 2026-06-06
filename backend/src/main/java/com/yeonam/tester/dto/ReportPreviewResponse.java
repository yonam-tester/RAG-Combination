package com.yeonam.tester.dto;

import java.time.LocalDateTime;

public class ReportPreviewResponse {
    private String reportId;
    private String reportFormat;
    private String content;
    private String disclaimer;
    private LocalDateTime generatedAt;

    public ReportPreviewResponse() {}

    public ReportPreviewResponse(String reportId, String reportFormat, String content, String disclaimer, LocalDateTime generatedAt) {
        this.reportId = reportId;
        this.reportFormat = reportFormat;
        this.content = content;
        this.disclaimer = disclaimer;
        this.generatedAt = generatedAt;
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public String getReportFormat() { return reportFormat; }
    public void setReportFormat(String reportFormat) { this.reportFormat = reportFormat; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public static ReportPreviewResponseBuilder builder() {
        return new ReportPreviewResponseBuilder();
    }

    public static class ReportPreviewResponseBuilder {
        private String reportId;
        private String reportFormat;
        private String content;
        private String disclaimer;
        private LocalDateTime generatedAt;

        public ReportPreviewResponseBuilder reportId(String reportId) {
            this.reportId = reportId;
            return this;
        }

        public ReportPreviewResponseBuilder reportFormat(String reportFormat) {
            this.reportFormat = reportFormat;
            return this;
        }

        public ReportPreviewResponseBuilder content(String content) {
            this.content = content;
            return this;
        }

        public ReportPreviewResponseBuilder disclaimer(String disclaimer) {
            this.disclaimer = disclaimer;
            return this;
        }

        public ReportPreviewResponseBuilder generatedAt(LocalDateTime generatedAt) {
            this.generatedAt = generatedAt;
            return this;
        }

        public ReportPreviewResponse build() {
            return new ReportPreviewResponse(reportId, reportFormat, content, disclaimer, generatedAt);
        }
    }
}
