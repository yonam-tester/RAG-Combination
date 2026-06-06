package com.yeonam.tester.service;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.ProjectCreateRequest;
import com.yeonam.tester.dto.ProjectResponse;
import com.yeonam.tester.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UploadedFileRepository fileRepository;
    private final ReportRepository reportRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final TestCaseRepository testCaseRepository;
    private final RequirementRepository requirementRepository;
    private final RiskItemRepository riskItemRepository;
    private final EvidenceRepository evidenceRepository;
    private final GithubService githubService;
    private final FileService fileService;
    private final S3Client s3Client;

    @Value("${aws.s3.buckets.documents}")
    private String documentsBucket;

    @Value("${aws.s3.buckets.reports}")
    private String reportsBucket;

    public ProjectService(ProjectRepository projectRepository,
                          UploadedFileRepository fileRepository,
                          ReportRepository reportRepository,
                          AnalysisJobRepository analysisJobRepository,
                          TestCaseRepository testCaseRepository,
                          RequirementRepository requirementRepository,
                          RiskItemRepository riskItemRepository,
                          EvidenceRepository evidenceRepository,
                          GithubService githubService,
                          FileService fileService,
                          S3Client s3Client) {
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.reportRepository = reportRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.testCaseRepository = testCaseRepository;
        this.requirementRepository = requirementRepository;
        this.riskItemRepository = riskItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.githubService = githubService;
        this.fileService = fileService;
        this.s3Client = s3Client;
    }

    /**
     * Registers a new project and verifies the GitHub repository integration.
     */
    @Transactional
    public ProjectResponse createProject(ProjectCreateRequest request) {
        String projectId = String.format("PRJ-%d-%04d", LocalDateTime.now().getYear(), (int) (Math.random() * 10000));
        
        String url = request.getGithubUrl();
        String branch = request.getGithubBranch();
        String integrationStatus = "NONE";

        if (url != null && !url.trim().isBlank()) {
            // Verify and fetch default branch if branch is not explicitly provided
            String defaultBranch = githubService.verifyRepositoryAndGetDefaultBranch(url);
            if (branch == null || branch.trim().isBlank()) {
                branch = defaultBranch;
            }
            integrationStatus = "SUCCESS";

            // Fetch and override project description with GitHub metadata if description is empty or to prioritize GitHub metadata
            try {
                String githubDesc = githubService.fetchRepositoryDescription(url);
                if (githubDesc != null && !githubDesc.trim().isEmpty()) {
                    request.setDescription(githubDesc);
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch repository description from GitHub: " + e.getMessage());
            }
        } else {
            url = null;
            branch = null;
        }

        Project project = Project.builder()
                .projectId(projectId)
                .name(request.getProjectName())
                .description(request.getDescription())
                .githubUrl(url)
                .githubBranch(branch)
                .integrationStatus(integrationStatus)
                .createdAt(LocalDateTime.now())
                .build();

        Project savedProject = projectRepository.save(project);

        // Async README.md collection
        if ("SUCCESS".equals(integrationStatus)) {
            try {
                String readmeContent = githubService.downloadReadme(url, branch);
                if (readmeContent != null && !readmeContent.trim().isEmpty()) {
                    fileService.uploadRawTextFile(savedProject.getProjectId(), "README.md", readmeContent, "REFERENCE");
                }
            } catch (Exception e) {
                System.err.println("Failed to auto-collect README.md for project: " + projectId + ". Error: " + e.getMessage());
            }
        }

        return mapToResponse(savedProject);
    }

    public List<ProjectResponse> getAllProjects() {
        return projectRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public Project getProjectEntity(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    public ProjectResponse getProjectById(String projectId) {
        Project project = getProjectEntity(projectId);
        return mapToResponse(project);
    }

    /**
     * Deletes a project and all associated database records and physical files in S3.
     * Uses direct JPQL DELETE queries to avoid Hibernate's SET NULL update pattern
     * which would violate NOT NULL constraints on FK columns.
     * Deletion order (child → parent): Evidence → RiskItem → TestCase → Requirement
     *                                   → Report (S3 + DB) → AnalysisJob → UploadedFile (S3 + DB) → Project
     */
    @Transactional
    public void deleteProject(String projectId) {
        Project project = getProjectEntity(projectId);

        // 1. Collect analysis IDs for this project
        List<AnalysisJob> jobs = analysisJobRepository.findByProject_ProjectId(projectId);
        List<String> analysisIds = jobs.stream()
                .map(AnalysisJob::getAnalysisId)
                .collect(Collectors.toList());

        if (!analysisIds.isEmpty()) {
            // 2. Delete Evidence (leaf node) — must be before TestCase
            evidenceRepository.deleteByAnalysisIds(analysisIds);

            // 3. Delete RiskItem (leaf node) — must be before TestCase
            riskItemRepository.deleteByAnalysisIds(analysisIds);

            // 4. Delete TestCase — must be before Requirement (TestCase.requirement_id FK)
            testCaseRepository.deleteByAnalysisIds(analysisIds);

            // 5. Delete Requirement — safe now that TestCases are gone
            requirementRepository.deleteByAnalysisIds(analysisIds);
        }

        // 6. Delete Reports from S3 then DB
        List<Report> reports = reportRepository.findByAnalysisJob_Project_ProjectId(projectId);
        for (Report report : reports) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(reportsBucket)
                        .key(report.getS3Path())
                        .build());
            } catch (S3Exception e) {
                System.err.println("Failed to delete S3 report " + report.getS3Path() + ": " + e.getMessage());
            }
        }
        if (!analysisIds.isEmpty()) {
            reportRepository.deleteByProjectId(projectId);
        }

        // 7. Delete AnalysisJobs
        if (!analysisIds.isEmpty()) {
            analysisJobRepository.deleteByProjectId(projectId);
        }

        // 8. Delete UploadedFiles from S3 then DB
        List<UploadedFile> files = fileRepository.findByProject_ProjectId(projectId);
        for (UploadedFile file : files) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(documentsBucket)
                        .key(file.getS3Path())
                        .build());
            } catch (S3Exception e) {
                System.err.println("Failed to delete S3 file " + file.getS3Path() + ": " + e.getMessage());
            }
            // RAG Vector DB 삭제 연쇄 호출
            fileService.deleteVectorsFromRagServer(file.getFileId());
        }
        fileRepository.deleteAll(files);

        // 9. Delete Project
        projectRepository.delete(project);
    }

    private ProjectResponse mapToResponse(Project project) {
        return ProjectResponse.builder()
                .projectId(project.getProjectId())
                .projectName(project.getName())
                .description(project.getDescription())
                .githubUrl(project.getGithubUrl())
                .githubBranch(project.getGithubBranch())
                .integrationStatus(project.getIntegrationStatus())
                .createdAt(project.getCreatedAt())
                .build();
    }
}
