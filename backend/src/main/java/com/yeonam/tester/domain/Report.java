package com.yeonam.tester.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "report")
public class Report {

    @Id
    @Column(name = "report_id")
    private String reportId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", nullable = false)
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = true)
    private UploadedFile uploadedFile;

    @Column(name = "s3_path", nullable = false)
    private String s3Path;

    @Column(name = "format", nullable = false)
    private String format;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<ReportTestCase> reportTestCases = new java.util.ArrayList<>();

    public Report() {}

    public Report(String reportId, AnalysisJob analysisJob, UploadedFile uploadedFile, String s3Path, String format, LocalDateTime createdAt) {
        this.reportId = reportId;
        this.analysisJob = analysisJob;
        this.uploadedFile = uploadedFile;
        this.s3Path = s3Path;
        this.format = format;
        this.createdAt = createdAt;
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public AnalysisJob getAnalysisJob() { return analysisJob; }
    public void setAnalysisJob(AnalysisJob analysisJob) { this.analysisJob = analysisJob; }

    public UploadedFile getUploadedFile() { return uploadedFile; }
    public void setUploadedFile(UploadedFile uploadedFile) { this.uploadedFile = uploadedFile; }

    public String getS3Path() { return s3Path; }
    public void setS3Path(String s3Path) { this.s3Path = s3Path; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public java.util.List<ReportTestCase> getReportTestCases() { return reportTestCases; }
    public void setReportTestCases(java.util.List<ReportTestCase> reportTestCases) { this.reportTestCases = reportTestCases; }

    public static ReportBuilder builder() {
        return new ReportBuilder();
    }

    public static class ReportBuilder {
        private String reportId;
        private AnalysisJob analysisJob;
        private UploadedFile uploadedFile;
        private String s3Path;
        private String format;
        private LocalDateTime createdAt;

        public ReportBuilder reportId(String reportId) {
            this.reportId = reportId;
            return this;
        }

        public ReportBuilder analysisJob(AnalysisJob analysisJob) {
            this.analysisJob = analysisJob;
            return this;
        }

        public ReportBuilder uploadedFile(UploadedFile uploadedFile) {
            this.uploadedFile = uploadedFile;
            return this;
        }

        public ReportBuilder s3Path(String s3Path) {
            this.s3Path = s3Path;
            return this;
        }

        public ReportBuilder format(String format) {
            this.format = format;
            return this;
        }

        public ReportBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Report build() {
            return new Report(reportId, analysisJob, uploadedFile, s3Path, format, createdAt);
        }
    }
}
