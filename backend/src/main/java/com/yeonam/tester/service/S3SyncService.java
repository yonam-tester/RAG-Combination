package com.yeonam.tester.service;

import com.yeonam.tester.domain.Project;
import com.yeonam.tester.domain.UploadedFile;
import com.yeonam.tester.repository.ProjectRepository;
import com.yeonam.tester.repository.UploadedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.LocalDateTime;
import java.util.Map;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class S3SyncService {

    private static final Logger log = LoggerFactory.getLogger(S3SyncService.class);

    private final S3Client s3Client;
    private final ProjectRepository projectRepository;
    private final UploadedFileRepository fileRepository;

    @Value("${aws.s3.buckets.documents}")
    private String documentsBucket;

    private static final Pattern KEY_PATTERN = Pattern.compile("projects/([^/]+)/([^_]+)_(.+)");

    public S3SyncService(S3Client s3Client,
                           ProjectRepository projectRepository,
                           UploadedFileRepository fileRepository) {
        this.s3Client = s3Client;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
    }

    /**
     * Scans the documents S3 bucket and restores missing Project and UploadedFile records in the database.
     */
    @Transactional
    public void syncDatabaseFromS3() {
        log.info("Starting database synchronization from S3 bucket: {}", documentsBucket);
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(documentsBucket)
                    .prefix("projects/")
                    .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            
            for (S3Object s3Object : listResponse.contents()) {
                String key = s3Object.key();
                Matcher matcher = KEY_PATTERN.matcher(key);
                
                if (matcher.matches()) {
                    String projectId = matcher.group(1);
                    String fileId = matcher.group(2);
                    String fileName = matcher.group(3);

                    log.debug("Found S3 object key: {}. Parsed -> projectId: {}, fileId: {}, fileName: {}", 
                            key, projectId, fileId, fileName);

                    // Fetch headObject to retrieve full project metadata from S3
                    String projectName = "복구된 프로젝트_" + projectId;
                    String projectDesc = "MinIO S3 동기화에 의해 복구된 프로젝트입니다.";
                    String githubUrl = null;
                    String githubBranch = null;
                    String integrationStatus = "NONE";
                    LocalDateTime createdAt = LocalDateTime.now();

                    try {
                        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                                .bucket(documentsBucket)
                                .key(key)
                                .build();
                        HeadObjectResponse headResponse = s3Client.headObject(headRequest);
                        Map<String, String> metadata = headResponse.metadata();

                        if (metadata != null && metadata.containsKey("project-name")) {
                            projectName = URLDecoder.decode(metadata.get("project-name"), StandardCharsets.UTF_8.toString());
                            if (metadata.containsKey("project-description")) {
                                String desc = metadata.get("project-description");
                                projectDesc = desc.isEmpty() ? "" : URLDecoder.decode(desc, StandardCharsets.UTF_8.toString());
                            }
                            if (metadata.containsKey("project-github-url")) {
                                githubUrl = metadata.get("project-github-url");
                            }
                            if (metadata.containsKey("project-github-branch")) {
                                githubBranch = metadata.get("project-github-branch");
                            }
                            if (metadata.containsKey("project-integration-status")) {
                                integrationStatus = metadata.get("project-integration-status");
                            }
                            if (metadata.containsKey("project-created-at")) {
                                try {
                                    createdAt = LocalDateTime.parse(metadata.get("project-created-at"));
                                } catch (Exception parseEx) {
                                    log.warn("Failed to parse project-created-at: {}", metadata.get("project-created-at"));
                                }
                            }
                        }
                    } catch (Exception headEx) {
                        log.warn("Failed to fetch headObject for key: {}. Falling back to default project details.", key, headEx);
                    }

                    final String finalProjectName = projectName;
                    final String finalProjectDesc = projectDesc;
                    final String finalGithubUrl = githubUrl;
                    final String finalGithubBranch = githubBranch;
                    final String finalIntegrationStatus = integrationStatus;
                    final LocalDateTime finalCreatedAt = createdAt;

                    // 1. Recover Project if missing
                    Project project = projectRepository.findById(projectId).orElseGet(() -> {
                        log.info("Project {} not found in database. Restoring from S3 metadata (Name: {})...", projectId, finalProjectName);
                        Project newProject = Project.builder()
                                .projectId(projectId)
                                .name(finalProjectName)
                                .description(finalProjectDesc)
                                .githubUrl(finalGithubUrl)
                                .githubBranch(finalGithubBranch)
                                .integrationStatus(finalIntegrationStatus)
                                .createdAt(finalCreatedAt)
                                .build();
                        return projectRepository.save(newProject);
                    });

                    // 2. Recover UploadedFile if missing
                    if (!fileRepository.existsById(fileId)) {
                        log.info("UploadedFile {} not found in database. Restoring from S3 metadata...", fileId);
                        UploadedFile newFile = UploadedFile.builder()
                                .fileId(fileId)
                                .project(project)
                                .fileName(fileName)
                                .fileType("REQUIREMENT_SPEC")
                                .s3Path(key)
                                .status("DONE") // Restored because physical file exists
                                .build();
                        fileRepository.save(newFile);
                    }
                }
            }
            log.info("Database synchronization from S3 completed successfully.");
        } catch (Exception e) {
            log.error("Failed to sync database from S3 bucket: {}", e.getMessage(), e);
        }
    }
}
