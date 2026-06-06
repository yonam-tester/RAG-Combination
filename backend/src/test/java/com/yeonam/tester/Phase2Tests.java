package com.yeonam.tester;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.ProjectCreateRequest;
import com.yeonam.tester.dto.ReportCreateRequest;
import com.yeonam.tester.dto.ReportResponse;
import com.yeonam.tester.repository.*;
import com.yeonam.tester.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Phase2Tests {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private FileService fileService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportDownloadService reportDownloadService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UploadedFileRepository fileRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private RequirementRepository requirementRepository;

    @Test
    @Transactional
    void testFileValidationRules() {
        // Setup mock project
        Project mockProject = Project.builder()
                .projectId("PRJ-MOCK")
                .name("Mock project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(mockProject);

        // 1. Invalid file format
        MockMultipartFile badFormatFile = new MockMultipartFile(
                "file", "test_spec.hwp", "application/octet-stream", "dummy data".getBytes()
        );

        assertThrows(IllegalArgumentException.class, () -> {
            fileService.uploadFile("PRJ-MOCK", badFormatFile, "REQUIREMENT_SPEC");
        }, "Should reject unsupported extension (.hwp)");

        // 2. Excessively large file mock (21MB)
        byte[] largeBytes = new byte[21 * 1024 * 1024];
        MockMultipartFile largeFile = new MockMultipartFile(
                "file", "large_spec.pdf", "application/pdf", largeBytes
        );
        assertThrows(IllegalArgumentException.class, () -> {
            fileService.uploadFile("PRJ-MOCK", largeFile, "REQUIREMENT_SPEC");
        }, "Should reject file exceeding 20MB");
    }

    @Test
    @Transactional
    void testProjectCreationAndReadmeCollection() {
        // Create project without URL (no Git trigger)
        ProjectCreateRequest request = ProjectCreateRequest.builder()
                .projectName("Local Pay Project")
                .description("No Github integration test")
                .build();

        var response = projectService.createProject(request);
        assertNotNull(response.getProjectId());
        assertEquals("Local Pay Project", response.getProjectName());
        assertEquals("NONE", response.getIntegrationStatus());

        Project savedEntity = projectRepository.findById(response.getProjectId()).orElse(null);
        assertNotNull(savedEntity);
    }

    @Test
    @Transactional
    void testCascadeDeleteAndS3CompletePurge() {
        // 1. Setup a project
        Project mockProject = Project.builder()
                .projectId("PRJ-CASCADE-TEST")
                .name("Cascade Test Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        mockProject = projectRepository.saveAndFlush(mockProject);

        // 2. Upload file to project
        MockMultipartFile file = new MockMultipartFile(
                "file", "test_rules.md", "text/markdown", "# Test requirement specification".getBytes(StandardCharsets.UTF_8)
        );
        
        UploadedFile uploadedFile = fileService.uploadFile("PRJ-CASCADE-TEST", file, "REQUIREMENT_SPEC");
        assertNotNull(uploadedFile.getFileId());
        
        // 3. Create dummy analysis job and report
        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-CASCADE-TEST")
                .project(mockProject)
                .status("COMPLETED")
                .summary("Analysis summary")
                .build();
        analysisJobRepository.save(job);

        // Insert a requirement and test case
        Requirement req = Requirement.builder()
                .requirementId("REQ-CASCADE-TEST")
                .analysisJob(job)
                .requirementText("System must allow logins")
                .build();
        requirementRepository.save(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-CASCADE-TEST")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Login validation test")
                .testScenario("Type valid credentials")
                .expectedResult("Welcome user screen")
                .priority("HIGH")
                .build();
        testCaseRepository.save(tc);

        // Save a report
        ReportCreateRequest repReq = ReportCreateRequest.builder().reportFormat("MARKDOWN").build();
        ReportResponse repResp = reportService.generateReport("ANL-CASCADE-TEST", repReq);
        assertNotNull(repResp.getReportId());

        // 4. Run Delete
        projectService.deleteProject("PRJ-CASCADE-TEST");

        // 5. Verify RDB and cascades are fully cleared
        assertFalse(projectRepository.existsById("PRJ-CASCADE-TEST"));
        assertFalse(fileRepository.existsById(uploadedFile.getFileId()));
        assertFalse(analysisJobRepository.existsById("ANL-CASCADE-TEST"));
        assertFalse(requirementRepository.existsById("REQ-CASCADE-TEST"));
        assertFalse(testCaseRepository.existsById("TC-CASCADE-TEST"));
        assertFalse(reportRepository.existsById(repResp.getReportId()));
    }

    @Test
    @Transactional
    void testReportDownloadRegenerationFallback() {
        // Setup Analysis Job
        Project mockProject = Project.builder()
                .projectId("PRJ-DOWNLOAD-TEST")
                .name("Download Fallback Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        mockProject = projectRepository.saveAndFlush(mockProject);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-DOWNLOAD-TEST")
                .project(mockProject)
                .status("COMPLETED")
                .summary("Download test analysis summary")
                .build();
        analysisJobRepository.save(job);

        Requirement req = Requirement.builder()
                .requirementId("REQ-DOWNLOAD-TEST")
                .analysisJob(job)
                .requirementText("Must support card billing")
                .build();
        requirementRepository.save(req);

        TestCase tc = TestCase.builder()
                .testCaseId("TC-DOWNLOAD-TEST")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Card limit error")
                .testScenario("Pay with insufficient funds")
                .expectedResult("Fail billing response")
                .priority("HIGH")
                .build();
        testCaseRepository.save(tc);

        // Generate report
        ReportResponse response = reportService.generateReport("ANL-DOWNLOAD-TEST", ReportCreateRequest.builder().reportFormat("MARKDOWN").build());
        String reportId = response.getReportId();

        // Simulate S3 file lost by deleting S3 physical object but keeping DB report record
        Report report = reportRepository.findById(reportId).orElseThrow();
        // Delete S3 object manually
        fileService.deleteFileFromS3(report.getS3Path());

        // Invoke download download bytes (should trigger fallback regeneration silently)
        byte[] bytes = reportDownloadService.downloadReportBytes(reportId);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0, "Should successfully reconstruct report bytes on-the-fly");
        
        // Cleanup
        reportService.deleteReport(reportId);
        projectService.deleteProject("PRJ-DOWNLOAD-TEST");
    }
}
