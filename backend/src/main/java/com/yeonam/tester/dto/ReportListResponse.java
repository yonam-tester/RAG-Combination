package com.yeonam.tester.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ReportListResponse {
    private List<ReportItemDto> reports;

    public ReportListResponse() {}

    public ReportListResponse(List<ReportItemDto> reports) {
        this.reports = reports;
    }

    public List<ReportItemDto> getReports() { return reports; }
    public void setReports(List<ReportItemDto> reports) { this.reports = reports; }

    public static ReportListResponseBuilder builder() {
        return new ReportListResponseBuilder();
    }

    public static class ReportItemDto {
        private String reportId;
        private String analysisId;
        private String reportFormat;
        private String status;
        private LocalDateTime generatedAt;

        public ReportItemDto() {}

        public ReportItemDto(String reportId, String analysisId, String reportFormat, String status, LocalDateTime generatedAt) {
            this.reportId = reportId;
            this.analysisId = analysisId;
            this.reportFormat = reportFormat;
            this.status = status;
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

        public LocalDateTime getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

        public static ReportItemDtoBuilder builder() {
            return new ReportItemDtoBuilder();
        }

        public static class ReportItemDtoBuilder {
            private String reportId;
            private String analysisId;
            private String reportFormat;
            private String status;
            private LocalDateTime generatedAt;

            public ReportItemDtoBuilder reportId(String reportId) {
                this.reportId = reportId;
                return this;
            }

            public ReportItemDtoBuilder analysisId(String analysisId) {
                this.analysisId = analysisId;
                return this;
            }

            public ReportItemDtoBuilder reportFormat(String reportFormat) {
                this.reportFormat = reportFormat;
                return this;
            }

            public ReportItemDtoBuilder status(String status) {
                this.status = status;
                return this;
            }

            public ReportItemDtoBuilder generatedAt(LocalDateTime generatedAt) {
                this.generatedAt = generatedAt;
                return this;
            }

            public ReportItemDto build() {
                return new ReportItemDto(reportId, analysisId, reportFormat, status, generatedAt);
            }
        }
    }

    public static class ReportListResponseBuilder {
        private List<ReportItemDto> reports;

        public ReportListResponseBuilder reports(List<ReportItemDto> reports) {
            this.reports = reports;
            return this;
        }

        public ReportListResponse build() {
            return new ReportListResponse(reports);
        }
    }
}
