package com.yeonam.tester.dto;

public class AnalysisStatusResponse {
    private String analysisId;
    private String status;
    private String message;
    private Integer progressPercentage;

    public AnalysisStatusResponse() {}

    public AnalysisStatusResponse(String analysisId, String status, String message, Integer progressPercentage) {
        this.analysisId = analysisId;
        this.status = status;
        this.message = message;
        this.progressPercentage = progressPercentage;
    }

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getProgressPercentage() { return progressPercentage; }
    public void setProgressPercentage(Integer progressPercentage) { this.progressPercentage = progressPercentage; }

    public static AnalysisStatusResponseBuilder builder() {
        return new AnalysisStatusResponseBuilder();
    }

    public static class AnalysisStatusResponseBuilder {
        private String analysisId;
        private String status;
        private String message;
        private Integer progressPercentage;

        public AnalysisStatusResponseBuilder analysisId(String analysisId) {
            this.analysisId = analysisId;
            return this;
        }

        public AnalysisStatusResponseBuilder status(String status) {
            this.status = status;
            return this;
        }

        public AnalysisStatusResponseBuilder message(String message) {
            this.message = message;
            return this;
        }

        public AnalysisStatusResponseBuilder progressPercentage(Integer progressPercentage) {
            this.progressPercentage = progressPercentage;
            return this;
        }

        public AnalysisStatusResponse build() {
            return new AnalysisStatusResponse(analysisId, status, message, progressPercentage);
        }
    }
}
