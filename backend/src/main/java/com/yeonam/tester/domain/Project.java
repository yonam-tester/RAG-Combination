package com.yeonam.tester.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "project")
public class Project {

    @Id
    @Column(name = "project_id")
    private String projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "github_url")
    private String githubUrl;

    @Column(name = "github_branch")
    private String githubBranch;

    @Column(name = "integration_status")
    private String integrationStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Project() {}

    public Project(String projectId, String name, String description, String githubUrl, String githubBranch, String integrationStatus, LocalDateTime createdAt) {
        this.projectId = projectId;
        this.name = name;
        this.description = description;
        this.githubUrl = githubUrl;
        this.githubBranch = githubBranch;
        this.integrationStatus = integrationStatus;
        this.createdAt = createdAt;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

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

    public static ProjectBuilder builder() {
        return new ProjectBuilder();
    }

    public static class ProjectBuilder {
        private String projectId;
        private String name;
        private String description;
        private String githubUrl;
        private String githubBranch;
        private String integrationStatus;
        private LocalDateTime createdAt;

        public ProjectBuilder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public ProjectBuilder name(String name) {
            this.name = name;
            return this;
        }

        public ProjectBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ProjectBuilder githubUrl(String githubUrl) {
            this.githubUrl = githubUrl;
            return this;
        }

        public ProjectBuilder githubBranch(String githubBranch) {
            this.githubBranch = githubBranch;
            return this;
        }

        public ProjectBuilder integrationStatus(String integrationStatus) {
            this.integrationStatus = integrationStatus;
            return this;
        }

        public ProjectBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Project build() {
            return new Project(projectId, name, description, githubUrl, githubBranch, integrationStatus, createdAt);
        }
    }
}
