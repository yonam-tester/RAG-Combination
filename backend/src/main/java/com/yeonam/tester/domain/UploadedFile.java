package com.yeonam.tester.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "uploaded_file")
public class UploadedFile {

    @Id
    @Column(name = "file_id")
    private String fileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(name = "s3_path", nullable = false)
    private String s3Path;

    @Column(name = "status", nullable = false)
    private String status;

    public UploadedFile() {}

    public UploadedFile(String fileId, Project project, String fileName, String fileType, String s3Path, String status) {
        this.fileId = fileId;
        this.project = project;
        this.fileName = fileName;
        this.fileType = fileType;
        this.s3Path = s3Path;
        this.status = status;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getS3Path() { return s3Path; }
    public void setS3Path(String s3Path) { this.s3Path = s3Path; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public static UploadedFileBuilder builder() {
        return new UploadedFileBuilder();
    }

    public static class UploadedFileBuilder {
        private String fileId;
        private Project project;
        private String fileName;
        private String fileType;
        private String s3Path;
        private String status;

        public UploadedFileBuilder fileId(String fileId) {
            this.fileId = fileId;
            return this;
        }

        public UploadedFileBuilder project(Project project) {
            this.project = project;
            return this;
        }

        public UploadedFileBuilder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public UploadedFileBuilder fileType(String fileType) {
            this.fileType = fileType;
            return this;
        }

        public UploadedFileBuilder s3Path(String s3Path) {
            this.s3Path = s3Path;
            return this;
        }

        public UploadedFileBuilder status(String status) {
            this.status = status;
            return this;
        }

        public UploadedFile build() {
            return new UploadedFile(fileId, project, fileName, fileType, s3Path, status);
        }
    }
}
