package com.yeonam.tester.dto;

import java.time.LocalDateTime;

public class FileResponse {
    private String documentId;
    private String fileName;
    private String fileType;
    private String status;
    private Long fileSizeByte;
    private LocalDateTime uploadedAt;

    public FileResponse() {}

    public FileResponse(String documentId, String fileName, String fileType, String status, Long fileSizeByte, LocalDateTime uploadedAt) {
        this.documentId = documentId;
        this.fileName = fileName;
        this.fileType = fileType;
        this.status = status;
        this.fileSizeByte = fileSizeByte;
        this.uploadedAt = uploadedAt;
    }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getFileSizeByte() { return fileSizeByte; }
    public void setFileSizeByte(Long fileSizeByte) { this.fileSizeByte = fileSizeByte; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }

    public static FileResponseBuilder builder() {
        return new FileResponseBuilder();
    }

    public static class FileResponseBuilder {
        private String documentId;
        private String fileName;
        private String fileType;
        private String status;
        private Long fileSizeByte;
        private LocalDateTime uploadedAt;

        public FileResponseBuilder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public FileResponseBuilder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public FileResponseBuilder fileType(String fileType) {
            this.fileType = fileType;
            return this;
        }

        public FileResponseBuilder status(String status) {
            this.status = status;
            return this;
        }

        public FileResponseBuilder fileSizeByte(Long fileSizeByte) {
            this.fileSizeByte = fileSizeByte;
            return this;
        }

        public FileResponseBuilder uploadedAt(LocalDateTime uploadedAt) {
            this.uploadedAt = uploadedAt;
            return this;
        }

        public FileResponse build() {
            return new FileResponse(documentId, fileName, fileType, status, fileSizeByte, uploadedAt);
        }
    }
}
