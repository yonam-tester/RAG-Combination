package com.yeonam.tester.dto;

import java.util.List;

public class ReportCreateRequest {
    private String reportFormat;
    private List<String> testCaseIds;
    private int targetScenarioCount;

    public ReportCreateRequest() {}

    public ReportCreateRequest(String reportFormat, List<String> testCaseIds, int targetScenarioCount) {
        this.reportFormat = reportFormat;
        this.testCaseIds = testCaseIds;
        this.targetScenarioCount = targetScenarioCount;
    }

    public String getReportFormat() { return reportFormat; }
    public void setReportFormat(String reportFormat) { this.reportFormat = reportFormat; }

    public List<String> getTestCaseIds() { return testCaseIds; }
    public void setTestCaseIds(List<String> testCaseIds) { this.testCaseIds = testCaseIds; }

    public int getTargetScenarioCount() { return targetScenarioCount; }
    public void setTargetScenarioCount(int targetScenarioCount) { this.targetScenarioCount = targetScenarioCount; }

    public static ReportCreateRequestBuilder builder() {
        return new ReportCreateRequestBuilder();
    }

    public static class ReportCreateRequestBuilder {
        private String reportFormat;
        private List<String> testCaseIds;
        private int targetScenarioCount;

        public ReportCreateRequestBuilder reportFormat(String reportFormat) {
            this.reportFormat = reportFormat;
            return this;
        }

        public ReportCreateRequestBuilder testCaseIds(List<String> testCaseIds) {
            this.testCaseIds = testCaseIds;
            return this;
        }

        public ReportCreateRequestBuilder targetScenarioCount(int targetScenarioCount) {
            this.targetScenarioCount = targetScenarioCount;
            return this;
        }

        public ReportCreateRequest build() {
            return new ReportCreateRequest(reportFormat, testCaseIds, targetScenarioCount);
        }
    }
}
