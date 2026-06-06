package com.yeonam.tester.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "requirement")
public class Requirement {

    @Id
    @Column(name = "requirement_id")
    private String requirementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", nullable = false)
    private AnalysisJob analysisJob;

    @Column(name = "requirement_text", nullable = false, columnDefinition = "TEXT")
    private String requirementText;

    public Requirement() {}

    public Requirement(String requirementId, AnalysisJob analysisJob, String requirementText) {
        this.requirementId = requirementId;
        this.analysisJob = analysisJob;
        this.requirementText = requirementText;
    }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public AnalysisJob getAnalysisJob() { return analysisJob; }
    public void setAnalysisJob(AnalysisJob analysisJob) { this.analysisJob = analysisJob; }

    public String getRequirementText() { return requirementText; }
    public void setRequirementText(String requirementText) { this.requirementText = requirementText; }

    public static RequirementBuilder builder() {
        return new RequirementBuilder();
    }

    public static class RequirementBuilder {
        private String requirementId;
        private AnalysisJob analysisJob;
        private String requirementText;

        public RequirementBuilder requirementId(String requirementId) {
            this.requirementId = requirementId;
            return this;
        }

        public RequirementBuilder analysisJob(AnalysisJob analysisJob) {
            this.analysisJob = analysisJob;
            return this;
        }

        public RequirementBuilder requirementText(String requirementText) {
            this.requirementText = requirementText;
            return this;
        }

        public Requirement build() {
            return new Requirement(requirementId, analysisJob, requirementText);
        }
    }
}
