package com.yeonam.tester.dto;

import java.util.List;

public class ReportCreateRequest {
    private String reportFormat;
    private List<String> testCaseIds;

    public ReportCreateRequest() {}

    public ReportCreateRequest(String reportFormat, List<String> testCaseIds) {
        this.reportFormat = reportFormat;
        this.testCaseIds = testCaseIds;
    }

    public String getReportFormat() { return reportFormat; }
    public void setReportFormat(String reportFormat) { this.reportFormat = reportFormat; }

    public List<String> getTestCaseIds() { return testCaseIds; }
    public void setTestCaseIds(List<String> testCaseIds) { this.testCaseIds = testCaseIds; }

    public static ReportCreateRequestBuilder builder() {
        return new ReportCreateRequestBuilder();
    }

    public static class ReportCreateRequestBuilder {
        private String reportFormat;
        private List<String> testCaseIds;

        public ReportCreateRequestBuilder reportFormat(String reportFormat) {
            this.reportFormat = reportFormat;
            return this;
        }

        public ReportCreateRequestBuilder testCaseIds(List<String> testCaseIds) {
            this.testCaseIds = testCaseIds;
            return this;
        }

        public ReportCreateRequest build() {
            return new ReportCreateRequest(reportFormat, testCaseIds);
        }
    }
}
