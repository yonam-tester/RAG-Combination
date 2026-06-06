package com.yeonam.tester.dto;

public class FileStatusResponse {
    private String documentId;
    private String status;
    private String processingStep;
    private String message;

    public FileStatusResponse() {}

    public FileStatusResponse(String documentId, String status, String processingStep, String message) {
        this.documentId = documentId;
        this.status = status;
        this.processingStep = processingStep;
        this.message = message;
    }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProcessingStep() { return processingStep; }
    public void setProcessingStep(String processingStep) { this.processingStep = processingStep; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public static FileStatusResponseBuilder builder() {
        return new FileStatusResponseBuilder();
    }

    public static class FileStatusResponseBuilder {
        private String documentId;
        private String status;
        private String processingStep;
        private String message;

        public FileStatusResponseBuilder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public FileStatusResponseBuilder status(String status) {
            this.status = status;
            return this;
        }

        public FileStatusResponseBuilder processingStep(String processingStep) {
            this.processingStep = processingStep;
            return this;
        }

        public FileStatusResponseBuilder message(String message) {
            this.message = message;
            return this;
        }

        public FileStatusResponse build() {
            return new FileStatusResponse(documentId, status, processingStep, message);
        }
    }
}
