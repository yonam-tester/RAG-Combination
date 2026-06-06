package com.yeonam.tester.service;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Service
public class FileService {

    private final S3Client s3Client;
    private final UploadedFileRepository fileRepository;
    private final ProjectRepository projectRepository;
    private final ReportRepository reportRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final TestCaseRepository testCaseRepository;
    private final RequirementRepository requirementRepository;
    private final RiskItemRepository riskItemRepository;
    private final EvidenceRepository evidenceRepository;
    private final FilePreprocessingService filePreprocessingService;

    @Value("${aws.s3.buckets.documents}")
    private String documentsBucket;

    @Value("${aws.s3.buckets.reports}")
    private String reportsBucket;

    @Value("${ai.server.url:http://localhost:8000}")
    private String aiServerUrl;

    private final HttpClient httpClient;

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20 MB
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("pdf", "md", "txt", "docx");

    public FileService(S3Client s3Client,
                       UploadedFileRepository fileRepository,
                       ProjectRepository projectRepository,
                       ReportRepository reportRepository,
                       AnalysisJobRepository analysisJobRepository,
                       TestCaseRepository testCaseRepository,
                       RequirementRepository requirementRepository,
                       RiskItemRepository riskItemRepository,
                       EvidenceRepository evidenceRepository,
                       FilePreprocessingService filePreprocessingService) {
        this.s3Client = s3Client;
        this.fileRepository = fileRepository;
        this.projectRepository = projectRepository;
        this.reportRepository = reportRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.testCaseRepository = testCaseRepository;
        this.requirementRepository = requirementRepository;
        this.riskItemRepository = riskItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.filePreprocessingService = filePreprocessingService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Validates and uploads a file to S3 and records it in H2 DB.
     */
    @Transactional
    public UploadedFile uploadFile(String projectId, MultipartFile file, String fileType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // 1. Validation
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds the 20MB limit.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("File must have a valid extension.");
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported file extension. Allowed extensions are: " + ALLOWED_EXTENSIONS);
        }

        // 2. Upload to S3
        String fileId = "DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String s3Path = String.format("projects/%s/%s_%s", projectId, fileId, originalFilename);

        try {
            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(documentsBucket)
                    .key(s3Path)
                    .contentType(file.getContentType())
                    .metadata(createProjectMetadata(project))
                    .build();

            s3Client.putObject(putOb, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload file input stream", e);
        } catch (S3Exception e) {
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }

        // 3. Save to DB
        UploadedFile uploadedFile = UploadedFile.builder()
                .fileId(fileId)
                .project(project)
                .fileName(originalFilename)
                .fileType(fileType != null ? fileType : "REQUIREMENT_SPEC")
                .s3Path(s3Path)
                .status("UPLOADED")
                .build();

        UploadedFile saved = fileRepository.save(uploadedFile);
        triggerAsyncPreprocessing(saved.getFileId());
        return saved;
    }

    /**
     * Internal helper to upload raw text directly (e.g. GitHub README.md)
     */
    @Transactional
    public UploadedFile uploadRawTextFile(String projectId, String fileName, String content, String fileType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        String fileId = "DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String s3Path = String.format("projects/%s/%s_%s", projectId, fileId, fileName);
        byte[] bytes = content.getBytes();

        try {
            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(documentsBucket)
                    .key(s3Path)
                    .contentType("text/markdown")
                    .metadata(createProjectMetadata(project))
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw new RuntimeException("S3 raw text upload failed: " + e.getMessage(), e);
        }

        UploadedFile uploadedFile = UploadedFile.builder()
                .fileId(fileId)
                .project(project)
                .fileName(fileName)
                .fileType(fileType)
                .s3Path(s3Path)
                .status("UPLOADED")
                .build();

        UploadedFile saved = fileRepository.save(uploadedFile);
        triggerAsyncPreprocessing(saved.getFileId());
        return saved;
    }

    private Map<String, String> createProjectMetadata(Project project) {
        Map<String, String> metadata = new HashMap<>();
        try {
            metadata.put("project-id", project.getProjectId());
            metadata.put("project-name", URLEncoder.encode(project.getName(), StandardCharsets.UTF_8.toString()));
            metadata.put("project-description", project.getDescription() != null ? URLEncoder.encode(project.getDescription(), StandardCharsets.UTF_8.toString()) : "");
            metadata.put("project-github-url", project.getGithubUrl() != null ? project.getGithubUrl() : "");
            metadata.put("project-github-branch", project.getGithubBranch() != null ? project.getGithubBranch() : "");
            metadata.put("project-integration-status", project.getIntegrationStatus() != null ? project.getIntegrationStatus() : "NONE");
            metadata.put("project-created-at", project.getCreatedAt() != null ? project.getCreatedAt().toString() : java.time.LocalDateTime.now().toString());
        } catch (Exception e) {
            metadata.put("project-id", project.getProjectId());
            metadata.put("project-name", project.getProjectId());
        }
        return metadata;
    }

    /**
     * Downloads file bytes from S3
     */
    public byte[] downloadFileBytes(UploadedFile uploadedFile) {
        try {
            GetObjectRequest getOb = GetObjectRequest.builder()
                    .bucket(documentsBucket)
                    .key(uploadedFile.getS3Path())
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getOb);
            return objectBytes.asByteArray();
        } catch (S3Exception e) {
            throw new RuntimeException("Failed to download file from S3: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes file from S3 bucket
     */
    public void deleteFileFromS3(String s3Path) {
        try {
            DeleteObjectRequest deleteOb = DeleteObjectRequest.builder()
                    .bucket(documentsBucket)
                    .key(s3Path)
                    .build();
            s3Client.deleteObject(deleteOb);
            System.out.println("Deleted S3 object: " + s3Path);
        } catch (S3Exception e) {
            System.err.println("Failed to delete S3 object: " + s3Path + ". Error: " + e.getMessage());
        }
    }

    /**
     * Deletes file meta-data from H2 DB and deletes from S3, and cascades deleting related reports/analysis jobs/test cases.
     */
    @Transactional
    public void deleteFile(String fileId) {
        UploadedFile uploadedFile = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));
        String projectId = uploadedFile.getProject().getProjectId();

        // 1. UploadedFile이 포함된 프로젝트와 연관된 모든 AnalysisJob 목록을 쿼리
        List<AnalysisJob> allJobs = analysisJobRepository.findByProject_ProjectId(projectId);

        // 2. fileId와 연관된 Report 조회
        List<Report> reports = reportRepository.findByUploadedFile_FileId(fileId);

        // 3. 삭제 대상 AnalysisJob ID 목록 식별 (reports가 해당 fileId로 생성된 것인지 파싱/매핑)
        List<String> targetAnalysisIds = reports.stream()
                .map(r -> r.getAnalysisJob().getAnalysisId())
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        if (!targetAnalysisIds.isEmpty()) {
            // 4. S3 물리 보고서 영구 삭제 (reportsBucket 버킷)
            for (Report r : reports) {
                if (targetAnalysisIds.contains(r.getAnalysisJob().getAnalysisId())) {
                    try {
                        DeleteObjectRequest deleteOb = DeleteObjectRequest.builder()
                                .bucket(reportsBucket)
                                .key(r.getS3Path())
                                .build();
                        s3Client.deleteObject(deleteOb);
                        System.out.println("Deleted S3 Report object: " + r.getS3Path());
                    } catch (Exception e) {
                        System.err.println("Failed to delete S3 report: " + r.getS3Path() + ". Error: " + e.getMessage());
                    }
                }
            }

            // 5. 역순 의존성에 맞게 DB 레코드 벌크 삭제
            // Evidence -> RiskItem -> TestCase -> Requirement -> Report -> AnalysisJob

            // a. TestCase IDs 수집
            List<TestCase> testCases = testCaseRepository.findAll();
            List<String> testCaseIds = testCases.stream()
                    .filter(tc -> targetAnalysisIds.contains(tc.getAnalysisJob().getAnalysisId()))
                    .map(TestCase::getTestCaseId)
                    .collect(java.util.stream.Collectors.toList());

            if (!testCaseIds.isEmpty()) {
                evidenceRepository.deleteByTestCaseIds(testCaseIds);
                riskItemRepository.deleteByTestCaseIds(testCaseIds);
            }

            testCaseRepository.deleteByAnalysisIds(targetAnalysisIds);
            requirementRepository.deleteByAnalysisIds(targetAnalysisIds);
            reportRepository.deleteByAnalysisIds(targetAnalysisIds);
            analysisJobRepository.deleteAllById(targetAnalysisIds);
        }

        // 6. S3에서 원본 업로드 문서 물리 파일 삭제
        deleteFileFromS3(uploadedFile.getS3Path());

        // 7. UploadedFile 삭제
        fileRepository.delete(uploadedFile);

        // 8. RAG Vector DB 연쇄 삭제
        deleteVectorsFromRagServer(fileId);
    }

    private void triggerAsyncPreprocessing(final String fileId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        filePreprocessingService.preprocessFile(fileId);
                    }
                }
            );
        } else {
            filePreprocessingService.preprocessFile(fileId);
        }
    }

    public List<UploadedFile> getFilesByProjectId(String projectId) {
        return fileRepository.findByProject_ProjectId(projectId);
    }

    public UploadedFile getFileById(String fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));
    }

    /**
     * Asynchronously requests the RAG server to delete vectors associated with the fileId.
     */
    public void deleteVectorsFromRagServer(String fileId) {
        String targetUrl = aiServerUrl + "/api/vectors/" + fileId;
        System.out.println("Sending async DELETE request to RAG server: " + targetUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .DELETE()
                    .timeout(Duration.ofSeconds(3))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            System.out.println("Successfully deleted vectors from RAG server for fileId: " + fileId);
                        } else {
                            System.err.println("RAG server returned error status for vector deletion: " + response.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("Failed to contact RAG server for vector deletion: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("Exception building delete request for RAG server: " + e.getMessage());
        }
    }
}
