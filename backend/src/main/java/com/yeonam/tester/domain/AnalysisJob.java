package com.yeonam.tester.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "analysis_job")
public class AnalysisJob {

    @Id
    @Column(name = "analysis_id")
    private String analysisId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "qa_perspective")
    private String qaPerspective;

    @Column(name = "custom_prompt", columnDefinition = "TEXT")
    private String customPrompt;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "missing_items_text", length = 2000)
    private String missingItemsText;

    public AnalysisJob() {}

    public AnalysisJob(String analysisId, Project project, String qaPerspective, String customPrompt, String summary, String status) {
        this.analysisId = analysisId;
        this.project = project;
        this.qaPerspective = qaPerspective;
        this.customPrompt = customPrompt;
        this.summary = summary;
        this.status = status;
    }

    public AnalysisJob(String analysisId, Project project, String qaPerspective, String customPrompt, String summary, String status, String missingItemsText) {
        this.analysisId = analysisId;
        this.project = project;
        this.qaPerspective = qaPerspective;
        this.customPrompt = customPrompt;
        this.summary = summary;
        this.status = status;
        this.missingItemsText = missingItemsText;
    }

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public String getQaPerspective() { return qaPerspective; }
    public void setQaPerspective(String qaPerspective) { this.qaPerspective = qaPerspective; }

    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMissingItemsText() { return missingItemsText; }
    public void setMissingItemsText(String missingItemsText) { this.missingItemsText = missingItemsText; }

    public static AnalysisJobBuilder builder() {
        return new AnalysisJobBuilder();
    }

    public static class AnalysisJobBuilder {
        private String analysisId;
        private Project project;
        private String qaPerspective;
        private String customPrompt;
        private String summary;
        private String status;
        private String missingItemsText;

        public AnalysisJobBuilder analysisId(String analysisId) {
            this.analysisId = analysisId;
            return this;
        }

        public AnalysisJobBuilder project(Project project) {
            this.project = project;
            return this;
        }

        public AnalysisJobBuilder qaPerspective(String qaPerspective) {
            this.qaPerspective = qaPerspective;
            return this;
        }

        public AnalysisJobBuilder customPrompt(String customPrompt) {
            this.customPrompt = customPrompt;
            return this;
        }

        public AnalysisJobBuilder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public AnalysisJobBuilder status(String status) {
            this.status = status;
            return this;
        }

        public AnalysisJobBuilder missingItemsText(String missingItemsText) {
            this.missingItemsText = missingItemsText;
            return this;
        }

        public AnalysisJob build() {
            return new AnalysisJob(analysisId, project, qaPerspective, customPrompt, summary, status, missingItemsText);
        }
    }
}
