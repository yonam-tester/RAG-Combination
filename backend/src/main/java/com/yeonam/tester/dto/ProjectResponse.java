package com.yeonam.tester.dto;

import java.time.LocalDateTime;

public class ProjectResponse {
    private String projectId;
    private String projectName;
    private String description;
    private String githubUrl;
    private String githubBranch;
    private String integrationStatus;
    private LocalDateTime createdAt;

    public ProjectResponse() {}

    public ProjectResponse(String projectId, String projectName, String description, String githubUrl, String githubBranch, String integrationStatus, LocalDateTime createdAt) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.description = description;
        this.githubUrl = githubUrl;
        this.githubBranch = githubBranch;
        this.integrationStatus = integrationStatus;
        this.createdAt = createdAt;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String githubUrl) { this.githubUrl = githubUrl; }

    public String getGithubBranch() { return githubBranch; }
    public void setGithubBranch(String githubBranch) { this.githubBranch = githubBranch; }

    public String getIntegrationStatus() { return integrationStatus; }
    public void setIntegrationStatus(String integrationStatus) { this.integrationStatus = integrationStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static ProjectResponseBuilder builder() {
        return new ProjectResponseBuilder();
    }

    public static class ProjectResponseBuilder {
        private String projectId;
        private String projectName;
        private String description;
        private String githubUrl;
        private String githubBranch;
        private String integrationStatus;
        private LocalDateTime createdAt;

        public ProjectResponseBuilder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public ProjectResponseBuilder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public ProjectResponseBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ProjectResponseBuilder githubUrl(String githubUrl) {
            this.githubUrl = githubUrl;
            return this;
        }

        public ProjectResponseBuilder githubBranch(String githubBranch) {
            this.githubBranch = githubBranch;
            return this;
        }

        public ProjectResponseBuilder integrationStatus(String integrationStatus) {
            this.integrationStatus = integrationStatus;
            return this;
        }

        public ProjectResponseBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ProjectResponse build() {
            return new ProjectResponse(projectId, projectName, description, githubUrl, githubBranch, integrationStatus, createdAt);
        }
    }
}
