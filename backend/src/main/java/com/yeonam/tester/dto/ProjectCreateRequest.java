package com.yeonam.tester.dto;

public class ProjectCreateRequest {
    private String projectName;
    private String description;
    private String githubUrl;
    private String githubBranch;

    public ProjectCreateRequest() {}

    public ProjectCreateRequest(String projectName, String description, String githubUrl, String githubBranch) {
        this.projectName = projectName;
        this.description = description;
        this.githubUrl = githubUrl;
        this.githubBranch = githubBranch;
    }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String githubUrl) { this.githubUrl = githubUrl; }

    public String getGithubBranch() { return githubBranch; }
    public void setGithubBranch(String githubBranch) { this.githubBranch = githubBranch; }

    public static ProjectCreateRequestBuilder builder() {
        return new ProjectCreateRequestBuilder();
    }

    public static class ProjectCreateRequestBuilder {
        private String projectName;
        private String description;
        private String githubUrl;
        private String githubBranch;

        public ProjectCreateRequestBuilder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public ProjectCreateRequestBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ProjectCreateRequestBuilder githubUrl(String githubUrl) {
            this.githubUrl = githubUrl;
            return this;
        }

        public ProjectCreateRequestBuilder githubBranch(String githubBranch) {
            this.githubBranch = githubBranch;
            return this;
        }

        public ProjectCreateRequest build() {
            return new ProjectCreateRequest(projectName, description, githubUrl, githubBranch);
        }
    }
}
