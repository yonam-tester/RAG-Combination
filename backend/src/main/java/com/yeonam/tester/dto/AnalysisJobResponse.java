package com.yeonam.tester.dto;

import java.time.LocalDateTime;

public class AnalysisJobResponse {
    private String analysisId;
    private String projectId;
    private String status;
    private LocalDateTime createdAt;
    private String qaPerspective;

    public AnalysisJobResponse() {}

    public AnalysisJobResponse(String analysisId, String projectId, String status, LocalDateTime createdAt, String qaPerspective) {
        this.analysisId = analysisId;
        this.projectId = projectId;
        this.status = status;
        this.createdAt = createdAt;
        this.qaPerspective = qaPerspective;
    }

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getQaPerspective() { return qaPerspective; }
    public void setQaPerspective(String qaPerspective) { this.qaPerspective = qaPerspective; }

    public static AnalysisJobResponseBuilder builder() {
        return new AnalysisJobResponseBuilder();
    }

    public static class AnalysisJobResponseBuilder {
        private String analysisId;
        private String projectId;
        private String status;
        private LocalDateTime createdAt;
        private String qaPerspective;

        public AnalysisJobResponseBuilder analysisId(String analysisId) {
            this.analysisId = analysisId;
            return this;
        }

        public AnalysisJobResponseBuilder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public AnalysisJobResponseBuilder status(String status) {
            this.status = status;
            return this;
        }

        public AnalysisJobResponseBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AnalysisJobResponseBuilder qaPerspective(String qaPerspective) {
            this.qaPerspective = qaPerspective;
            return this;
        }

        public AnalysisJobResponse build() {
            return new AnalysisJobResponse(analysisId, projectId, status, createdAt, qaPerspective);
        }
    }
}
