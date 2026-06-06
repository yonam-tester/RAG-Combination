package com.yeonam.tester;

import com.yeonam.tester.dto.ProjectCreateRequest;
import com.yeonam.tester.dto.ProjectResponse;
import com.yeonam.tester.service.ProjectService;
import com.yeonam.tester.service.GithubService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Phase6ExtensionsTests {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private GithubService githubService;

    @Autowired
    private com.yeonam.tester.repository.ProjectRepository projectRepository;

    @Autowired
    private com.yeonam.tester.repository.UploadedFileRepository uploadedFileRepository;

    @Autowired
    private com.yeonam.tester.repository.AnalysisJobRepository analysisJobRepository;

    @Autowired
    private com.yeonam.tester.repository.ReportRepository reportRepository;

    @Autowired
    private com.yeonam.tester.service.ReportService reportService;

    @Autowired
    private com.yeonam.tester.service.FileService fileService;

    @Autowired
    private com.yeonam.tester.service.AnalysisService analysisService;

    @Autowired
    private com.yeonam.tester.service.CallbackService callbackService;

    @Autowired
    private com.yeonam.tester.repository.RequirementRepository requirementRepository;

    @Autowired
    private com.yeonam.tester.repository.TestCaseRepository testCaseRepository;

    @Autowired
    private com.yeonam.tester.repository.EvidenceRepository evidenceRepository;

    @Autowired
    private com.yeonam.tester.repository.RiskItemRepository riskItemRepository;

    @Autowired
    private com.yeonam.tester.service.TestCaseService testCaseService;

    @Autowired
    private com.yeonam.tester.repository.ReportTestCaseRepository reportTestCaseRepository;

    @Autowired
    private com.yeonam.tester.service.ReportDownloadService reportDownloadService;

    @Test
    @Transactional
    void testGithubMetadataCollection() {
        // 1. Create project with public repo but no description
        ProjectCreateRequest request = ProjectCreateRequest.builder()
                .projectName("Octocat Hello World")
                .githubUrl("https://github.com/octocat/Hello-World")
                .githubBranch("master")
                .description("")
                .build();

        // 2. Perform creation - should verify and override description automatically
        ProjectResponse response = projectService.createProject(request);

        // 3. Assertions
        assertNotNull(response.getProjectId());
        assertEquals("SUCCESS", response.getIntegrationStatus());
        assertNotNull(response.getDescription());
        assertFalse(response.getDescription().trim().isEmpty());
        
        // Output result for verification
        System.out.println("Collected Description: " + response.getDescription());
    }

    @Test
    void testFetchDescriptionDirectly() {
        // Test direct fetching logic
        String description = githubService.fetchRepositoryDescription("https://github.com/octocat/Hello-World");
        assertNotNull(description);
        assertTrue(description.contains("My first repository on GitHub"));
    }

    @Test
    @Transactional
    void testReportMultiFilteringQuery() {
        // 1. Create a dummy project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("TEST-PRJ")
                .name("Test Project")
                .githubUrl("https://github.com/test/repo")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create dummy UploadedFiles
        com.yeonam.tester.domain.UploadedFile fileA = com.yeonam.tester.domain.UploadedFile.builder()
                .fileId("TEST-FILE-A")
                .project(project)
                .fileName("fileA.pdf")
                .fileType("pdf")
                .s3Path("s3://path/a")
                .status("PROCESSED")
                .build();
        com.yeonam.tester.domain.UploadedFile fileB = com.yeonam.tester.domain.UploadedFile.builder()
                .fileId("TEST-FILE-B")
                .project(project)
                .fileName("fileB.pdf")
                .fileType("pdf")
                .s3Path("s3://path/b")
                .status("PROCESSED")
                .build();
        uploadedFileRepository.save(fileA);
        uploadedFileRepository.save(fileB);

        // 3. Create dummy AnalysisJobs
        com.yeonam.tester.domain.AnalysisJob jobA = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("TEST-ANA-A")
                .project(project)
                .qaPerspective("Perspective A")
                .customPrompt("Prompt A")
                .status("COMPLETED")
                .build();
        com.yeonam.tester.domain.AnalysisJob jobB = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("TEST-ANA-B")
                .project(project)
                .qaPerspective("Perspective B")
                .customPrompt("Prompt B")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(jobA);
        analysisJobRepository.save(jobB);

        // 4. Create Reports
        com.yeonam.tester.domain.Report reportA = com.yeonam.tester.domain.Report.builder()
                .reportId("TEST-RPT-A")
                .analysisJob(jobA)
                .uploadedFile(fileA)
                .s3Path("s3://rpt/a")
                .format("MARKDOWN")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        com.yeonam.tester.domain.Report reportB = com.yeonam.tester.domain.Report.builder()
                .reportId("TEST-RPT-B")
                .analysisJob(jobB)
                .uploadedFile(fileB)
                .s3Path("s3://rpt/b")
                .format("MARKDOWN")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        reportRepository.save(reportA);
        reportRepository.save(reportB);

        // 5. Test Filters via service
        // Filter by fileA
        com.yeonam.tester.dto.ReportListResponse filterFileA = reportService.getReportsByProject("TEST-PRJ", "TEST-FILE-A", null);
        assertEquals(1, filterFileA.getReports().size());
        assertEquals("TEST-RPT-A", filterFileA.getReports().get(0).getReportId());

        // Filter by jobB (analysisId = TEST-ANA-B)
        com.yeonam.tester.dto.ReportListResponse filterJobB = reportService.getReportsByProject("TEST-PRJ", null, "TEST-ANA-B");
        assertEquals(1, filterJobB.getReports().size());
        assertEquals("TEST-RPT-B", filterJobB.getReports().get(0).getReportId());

        // Filter by fileA and jobB (should yield empty because TEST-RPT-A is jobA, TEST-RPT-B is fileB)
        com.yeonam.tester.dto.ReportListResponse filterBothEmpty = reportService.getReportsByProject("TEST-PRJ", "TEST-FILE-A", "TEST-ANA-B");
        assertEquals(0, filterBothEmpty.getReports().size());

        // No filters (should yield both reports)
        com.yeonam.tester.dto.ReportListResponse noFilter = reportService.getReportsByProject("TEST-PRJ", null, null);
        assertEquals(2, noFilter.getReports().size());
    }

    @Test
    @Transactional
    void testFileDeleteCascadeReportsAndS3() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("CASCADE-PRJ")
                .name("Cascade Test Project")
                .githubUrl("https://github.com/test/cascade")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create UploadedFile
        com.yeonam.tester.domain.UploadedFile file = com.yeonam.tester.domain.UploadedFile.builder()
                .fileId("CASCADE-DOC")
                .project(project)
                .fileName("cascade_doc.pdf")
                .fileType("pdf")
                .s3Path("projects/CASCADE-PRJ/CASCADE-DOC_cascade_doc.pdf")
                .status("PROCESSED")
                .build();
        uploadedFileRepository.save(file);

        // 3. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("CASCADE-ANL")
                .project(project)
                .qaPerspective("Perspective")
                .customPrompt("Prompt")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job);

        // 4. Create Requirement
        com.yeonam.tester.domain.Requirement req = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("CASCADE-REQ")
                .analysisJob(job)
                .requirementText("Requirement text")
                .build();
        requirementRepository.save(req);

        // 5. Create TestCase
        com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("CASCADE-TC")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Test Case Name")
                .testScenario("Scenario")
                .precondition("Precondition")
                .testSteps("1. Step 1")
                .expectedResult("Expected")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .build();
        testCaseRepository.save(tc);

        // 6. Create Evidence & RiskItem
        com.yeonam.tester.domain.Evidence ev = com.yeonam.tester.domain.Evidence.builder()
                .evidenceId("CASCADE-EV")
                .testCase(tc)
                .chunkId("chunk-1")
                .evidenceText("Evidence text")
                .sourceName("cascade_doc.pdf")
                .sourceSection("Section 1")
                .build();
        evidenceRepository.save(ev);

        com.yeonam.tester.domain.RiskItem risk = com.yeonam.tester.domain.RiskItem.builder()
                .riskId("CASCADE-RISK")
                .testCase(tc)
                .riskType("Security")
                .build();
        riskItemRepository.save(risk);

        // 7. Create Report
        com.yeonam.tester.domain.Report report = com.yeonam.tester.domain.Report.builder()
                .reportId("CASCADE-RPT")
                .analysisJob(job)
                .uploadedFile(file)
                .s3Path("reports/CASCADE-ANL/CASCADE-RPT.md")
                .format("MARKDOWN")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        reportRepository.save(report);

        // 8. Delete File - should trigger cascading delete
        fileService.deleteFile("CASCADE-DOC");

        // 9. Asserts
        assertFalse(uploadedFileRepository.existsById("CASCADE-DOC"));
        assertFalse(reportRepository.existsById("CASCADE-RPT"));
        assertFalse(analysisJobRepository.existsById("CASCADE-ANL"));
        assertFalse(requirementRepository.existsById("CASCADE-REQ"));
        assertFalse(testCaseRepository.existsById("CASCADE-TC"));
        assertFalse(evidenceRepository.existsById("CASCADE-EV"));
        assertFalse(riskItemRepository.existsById("CASCADE-RISK"));
    }

    @Test
    @Transactional
    void testStartAnalysisWithTargetDocumentsAndQaPerspectives() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("TEST-START-PRJ")
                .name("Start Test Project")
                .githubUrl("https://github.com/test/start")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create UploadedFiles (Doc A, Doc B)
        com.yeonam.tester.domain.UploadedFile fileA = com.yeonam.tester.domain.UploadedFile.builder()
                .fileId("TEST-DOC-A")
                .project(project)
                .fileName("docA.pdf")
                .fileType("pdf")
                .s3Path("projects/TEST-START-PRJ/TEST-DOC-A_docA.pdf")
                .status("DONE")
                .build();
        com.yeonam.tester.domain.UploadedFile fileB = com.yeonam.tester.domain.UploadedFile.builder()
                .fileId("TEST-DOC-B")
                .project(project)
                .fileName("docB.pdf")
                .fileType("pdf")
                .s3Path("projects/TEST-START-PRJ/TEST-DOC-B_docB.pdf")
                .status("DONE")
                .build();
        uploadedFileRepository.save(fileA);
        uploadedFileRepository.save(fileB);

        // 3. Prepare Request with only Doc A and QA perspectives: SECURITY, PERFORMANCE
        com.yeonam.tester.dto.AnalysisCreateRequest request = com.yeonam.tester.dto.AnalysisCreateRequest.builder()
                .targetDocumentIds(java.util.Collections.singletonList("TEST-DOC-A"))
                .qaPerspectives(java.util.Arrays.asList("SECURITY", "PERFORMANCE"))
                .customPrompt("추가 지시사항")
                .build();

        // 4. Start analysis
        com.yeonam.tester.dto.AnalysisJobResponse response = analysisService.startAnalysis("TEST-START-PRJ", request);

        // 5. Assertions
        assertNotNull(response.getAnalysisId());
        
        // Retrieve saved job to check merged custom prompt
        com.yeonam.tester.domain.AnalysisJob job = analysisJobRepository.findById(response.getAnalysisId()).orElse(null);
        assertNotNull(job);
        assertEquals("SECURITY,PERFORMANCE", job.getQaPerspective());
        
        // Custom prompt should contain both Security and Performance guides + original custom prompt
        String customPrompt = job.getCustomPrompt();
        assertNotNull(customPrompt);
        assertTrue(customPrompt.contains("[QA 검증 관점 가이드]"));
        assertTrue(customPrompt.contains("SECURITY: 인증, 인가, 데이터 오용, 입력값 유효성 검증을 위한 침투형 시나리오 수립에 집중하라."));
        assertTrue(customPrompt.contains("PERFORMANCE: 시스템 부하, 응답 지연, 리소스 병목 현상 및 동시성 처리에 대한 한계 조건 검증에 집중하라."));
        assertTrue(customPrompt.contains("추가 지시사항"));
    }

    @Test
    @Transactional
    void testWebhookCallbackEvidencePersistence() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("TEST-CALLBACK-PRJ")
                .name("Callback Test Project")
                .githubUrl("https://github.com/test/callback")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("TEST-CALLBACK-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt")
                .status("WAITING")
                .build();
        analysisJobRepository.save(job);

        // 3. Prepare Mock Callback Request
        com.yeonam.tester.dto.AnalysisCallbackRequest.EvidenceDto evDto = com.yeonam.tester.dto.AnalysisCallbackRequest.EvidenceDto.builder()
                .chunkId("CHK-001")
                .evidenceText("로그인 시 잘못된 비밀번호를 5회 연속 입력하면 계정이 10분간 잠금 상태로 전환되어야 한다.")
                .sourceName("요구사항_명세서.md")
                .sourceSection("3.2 사용자 인증 및 보안 설정")
                .build();

        com.yeonam.tester.dto.AnalysisCallbackRequest.TestCaseDto tcDto = com.yeonam.tester.dto.AnalysisCallbackRequest.TestCaseDto.builder()
                .testCaseId("TC-TEST-001")
                .requirementId("REQ-TEST-001")
                .requirementText("비밀번호 5회 실패 잠금 요구사항")
                .testCaseName("패스워드 5회 연속 실패 계정 잠금 검증")
                .testScenario("시나리오")
                .precondition("사전조건")
                .testSteps(java.util.Arrays.asList("1. 로그인 시도", "2. 5회 실패"))
                .expectedResult("계정 잠김")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .riskTags(java.util.Collections.singletonList("보안"))
                .evidences(java.util.Collections.singletonList(evDto))
                .category("test_technique")
                .technique("Negative Testing Philosophy")
                .tddHint("Callback TDD Hint")
                .negativeScenario("Callback Negative Scenario")
                .build();

        com.yeonam.tester.dto.AnalysisCallbackRequest callbackRequest = com.yeonam.tester.dto.AnalysisCallbackRequest.builder()
                .summary("요약 텍스트")
                .testCases(java.util.Collections.singletonList(tcDto))
                .status("SUCCESS")
                .build();

        // 4. Trigger Webhook callback Service method
        callbackService.processCallback("TEST-CALLBACK-ANL", callbackRequest);

        // 5. Verification
        com.yeonam.tester.domain.AnalysisJob completedJob = analysisJobRepository.findById("TEST-CALLBACK-ANL").orElse(null);
        assertNotNull(completedJob);
        assertEquals("COMPLETED", completedJob.getStatus());

        java.util.List<com.yeonam.tester.domain.Evidence> savedEvidences = evidenceRepository.findByTestCase_TestCaseIdIn(java.util.Collections.singletonList("TC-TEST-001"));
        assertEquals(1, savedEvidences.size());
        
        com.yeonam.tester.domain.Evidence savedEv = savedEvidences.get(0);
        assertEquals("CHK-001", savedEv.getChunkId());
        assertEquals("요구사항_명세서.md", savedEv.getSourceName());
        assertEquals("3.2 사용자 인증 및 보안 설정", savedEv.getSourceSection());
        assertEquals("로그인 시 잘못된 비밀번호를 5회 연속 입력하면 계정이 10분간 잠금 상태로 전환되어야 한다.", savedEv.getEvidenceText());

        com.yeonam.tester.domain.TestCase savedTc = testCaseRepository.findById("TC-TEST-001").orElse(null);
        assertNotNull(savedTc);
        assertEquals("test_technique", savedTc.getCategory());
        assertEquals("Negative Testing Philosophy", savedTc.getTechnique());
        assertEquals("Callback TDD Hint", savedTc.getTddHint());
        assertEquals("Callback Negative Scenario", savedTc.getNegativeScenario());
    }

    @Test
    void testFilePreprocessingPipeline() throws Exception {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("PRE-PRJ")
                .name("Preprocessing Test Project")
                .githubUrl("https://github.com/test/pre")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Upload raw text file (This triggers async preprocessing)
        com.yeonam.tester.domain.UploadedFile file = fileService.uploadRawTextFile(
                "PRE-PRJ", 
                "test_spec.md", 
                "# 요구사항\n1. 로그인 기능 개발.", 
                "REQUIREMENT_SPEC"
        );

        assertNotNull(file);
        assertEquals("test_spec.md", file.getFileName());
        
        System.out.println("Initially uploaded file status: " + file.getStatus());

        // 3. Wait for async thread to complete
        Thread.sleep(1500);

        // 4. Retrieve from DB and assert status is DONE
        com.yeonam.tester.domain.UploadedFile processedFile = uploadedFileRepository.findById(file.getFileId()).orElse(null);
        assertNotNull(processedFile);
        assertEquals("DONE", processedFile.getStatus());
    }

    @Test
    @Transactional
    void testTestCaseCRUD() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("CRUD-PRJ")
                .name("CRUD Test Project")
                .githubUrl("https://github.com/test/crud")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("CRUD-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job);

        // 3. Create Requirement
        com.yeonam.tester.domain.Requirement req = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("CRUD-REQ")
                .analysisJob(job)
                .requirementText("Requirement text")
                .build();
        requirementRepository.save(req);

        // 4. Create TestCase
        com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("CRUD-TC")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Original Test Case Name")
                .testScenario("Original Scenario")
                .precondition("Original Precondition")
                .testSteps("1. Step 1\n2. Step 2")
                .expectedResult("Original Expected")
                .priority("MEDIUM")
                .confidenceLevel("HIGH")
                .build();
        testCaseRepository.save(tc);

        // 5. Create Evidence & RiskItem
        com.yeonam.tester.domain.Evidence ev = com.yeonam.tester.domain.Evidence.builder()
                .evidenceId("CRUD-EV")
                .testCase(tc)
                .chunkId("chunk-crud")
                .evidenceText("Evidence text")
                .sourceName("crud_doc.pdf")
                .sourceSection("Section 1")
                .build();
        evidenceRepository.save(ev);

        com.yeonam.tester.domain.RiskItem risk = com.yeonam.tester.domain.RiskItem.builder()
                .riskId("CRUD-RISK")
                .testCase(tc)
                .riskType("Security")
                .build();
        riskItemRepository.save(risk);

        // 6. Read Verification (Get all test cases belonging to project)
        java.util.List<com.yeonam.tester.dto.AnalysisResultResponse.TestCaseDto> list = testCaseService.getTestCasesByProject("CRUD-PRJ");
        assertEquals(1, list.size());
        com.yeonam.tester.dto.AnalysisResultResponse.TestCaseDto fetchedDto = list.get(0);
        assertEquals("CRUD-TC", fetchedDto.getTestCaseId());
        assertEquals("Original Test Case Name", fetchedDto.getTestCaseName());
        assertEquals(1, fetchedDto.getEvidences().size());
        assertEquals("crud_doc.pdf", fetchedDto.getEvidences().get(0).getSourceName());
        assertEquals(1, fetchedDto.getRiskTags().size());
        assertEquals("#Security", fetchedDto.getRiskTags().get(0));

        // 7. Update Verification
        com.yeonam.tester.dto.TestCaseUpdateRequest updateReq = new com.yeonam.tester.dto.TestCaseUpdateRequest(
                "Updated Test Case Name",
                "Updated Scenario",
                "Updated Precondition",
                java.util.Arrays.asList("Updated Step 1", "Updated Step 2"),
                "Updated Expected",
                "HIGH"
        );
        com.yeonam.tester.dto.AnalysisResultResponse.TestCaseDto updatedDto = testCaseService.updateTestCase("CRUD-TC", updateReq);
        assertEquals("Updated Test Case Name", updatedDto.getTestCaseName());
        assertEquals("Updated Scenario", updatedDto.getTestScenario());
        assertEquals("Updated Precondition", updatedDto.getPrecondition());
        assertEquals(2, updatedDto.getTestSteps().size());
        assertEquals("Updated Step 1", updatedDto.getTestSteps().get(0));
        assertEquals("Updated Expected", updatedDto.getExpectedResult());
        assertEquals("HIGH", updatedDto.getPriority());

        // Verify changes are in DB
        com.yeonam.tester.domain.TestCase updatedTc = testCaseRepository.findById("CRUD-TC").orElse(null);
        assertNotNull(updatedTc);
        assertEquals("Updated Test Case Name", updatedTc.getTestCaseName());

        // 8. Delete Verification
        testCaseService.deleteTestCase("CRUD-TC");
        assertFalse(testCaseRepository.existsById("CRUD-TC"));
        // Check cascade deletion of child records
        assertFalse(evidenceRepository.existsById("CRUD-EV"));
        assertFalse(riskItemRepository.existsById("CRUD-RISK"));
    }

    @Test
    @Transactional
    void testGenerateReportWithSelectedTestCases() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("SEL-RPT-PRJ")
                .name("Selected Report Test Project")
                .githubUrl("https://github.com/test/sel-rpt")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("SEL-RPT-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job);

        // 3. Create Requirement
        com.yeonam.tester.domain.Requirement req = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("SEL-RPT-REQ")
                .analysisJob(job)
                .requirementText("Requirement text")
                .build();
        requirementRepository.save(req);

        // 4. Create 2 TestCases
        com.yeonam.tester.domain.TestCase tc1 = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("SEL-TC-1")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Test Case 1")
                .testScenario("Scenario 1")
                .precondition("Precondition 1")
                .testSteps("1. Step 1")
                .expectedResult("Expected 1")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .build();
        com.yeonam.tester.domain.TestCase tc2 = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("SEL-TC-2")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Test Case 2")
                .testScenario("Scenario 2")
                .precondition("Precondition 2")
                .testSteps("1. Step 1")
                .expectedResult("Expected 2")
                .priority("LOW")
                .confidenceLevel("HIGH")
                .build();
        testCaseRepository.save(tc1);
        testCaseRepository.save(tc2);

        // 5. Prepare ReportCreateRequest with only tc1
        com.yeonam.tester.dto.ReportCreateRequest request = com.yeonam.tester.dto.ReportCreateRequest.builder()
                .reportFormat("MARKDOWN")
                .testCaseIds(java.util.Collections.singletonList("SEL-TC-1"))
                .build();

        // 6. Generate report
        com.yeonam.tester.dto.ReportResponse response = reportService.generateReport("SEL-RPT-ANL", request);

        // 7. Verification
        assertNotNull(response.getReportId());
        assertEquals("DONE", response.getStatus());

        com.yeonam.tester.domain.Report savedReport = reportRepository.findById(response.getReportId()).orElse(null);
        assertNotNull(savedReport);

        // Check mapping table records - should be exactly 1 mapping to SEL-TC-1
        java.util.List<com.yeonam.tester.domain.ReportTestCase> mappings = savedReport.getReportTestCases();
        assertEquals(1, mappings.size());
        assertEquals("SEL-TC-1", mappings.get(0).getTestCase().getTestCaseId());
    }

    @Test
    @Transactional
    void testDownloadOnTheFlyPdf() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("FLY-RPT-PRJ")
                .name("On The Fly Test Project")
                .githubUrl("https://github.com/test/fly-rpt")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("FLY-RPT-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job);

        // 3. Create Requirement & TestCase
        com.yeonam.tester.domain.Requirement req = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("FLY-RPT-REQ")
                .analysisJob(job)
                .requirementText("Requirement text")
                .build();
        requirementRepository.save(req);

        com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("FLY-TC-1")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Test Case")
                .testScenario("Scenario")
                .precondition("Precondition")
                .testSteps("1. Step 1")
                .expectedResult("Expected")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .build();
        testCaseRepository.save(tc);

        // 4. Generate report - this will write standard markdown to S3
        com.yeonam.tester.dto.ReportCreateRequest request = com.yeonam.tester.dto.ReportCreateRequest.builder()
                .reportFormat("PDF") // requested format PDF but cached as MARKDOWN
                .testCaseIds(java.util.Collections.singletonList("FLY-TC-1"))
                .build();
        com.yeonam.tester.dto.ReportResponse response = reportService.generateReport("FLY-RPT-ANL", request);
        assertNotNull(response.getReportId());

        // 5. Test Download PDF On-The-Fly
        byte[] pdfBytes = reportDownloadService.downloadReportBytes(response.getReportId(), "PDF");
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);

        // Test Download Markdown On-The-Fly
        byte[] mdBytes = reportDownloadService.downloadReportBytes(response.getReportId(), "MARKDOWN");
        assertNotNull(mdBytes);
        assertTrue(mdBytes.length > 0);
        
        // Assert they are different representation
        assertNotEquals(pdfBytes.length, mdBytes.length);
    }

    @Test
    @Transactional
    void testTestCaseEntityExtension() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("EXT-PRJ")
                .name("Extension Test Project")
                .githubUrl("https://github.com/test/ext")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("EXT-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job);

        // 3. Create Requirement
        com.yeonam.tester.domain.Requirement req = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("EXT-REQ")
                .analysisJob(job)
                .requirementText("Requirement text")
                .build();
        requirementRepository.save(req);

        // 4. Create TestCase with new extended fields
        com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("EXT-TC-001")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("New Fields Test")
                .testScenario("Scenario")
                .precondition("Precondition")
                .testSteps("1. Step 1")
                .expectedResult("Expected")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .category("test_technique")
                .technique("Negative Testing Philosophy")
                .tddHint("TDD Hint text")
                .negativeScenario("Negative Scenario text")
                .build();
        testCaseRepository.save(tc);

        // 5. Retrieve from DB and assert new fields
        com.yeonam.tester.domain.TestCase retrieved = testCaseRepository.findById("EXT-TC-001").orElse(null);
        assertNotNull(retrieved);
        assertEquals("test_technique", retrieved.getCategory());
        assertEquals("Negative Testing Philosophy", retrieved.getTechnique());
        assertEquals("TDD Hint text", retrieved.getTddHint());
        assertEquals("Negative Scenario text", retrieved.getNegativeScenario());
    }

    @Test
    @Transactional
    void testAnalysisTriggerWithCustomApiKey() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("KEY-PRJ")
                .name("API Key Test Project")
                .githubUrl("https://github.com/test/key")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Prepare AnalysisCreateRequest with a custom API key
        com.yeonam.tester.dto.AnalysisCreateRequest request = com.yeonam.tester.dto.AnalysisCreateRequest.builder()
                .targetDocumentIds(java.util.Collections.emptyList())
                .qaPerspectives(java.util.Collections.singletonList("SECURITY"))
                .customPrompt("보안 검증")
                .llmApiKey("sk-mock-user-api-key-99999")
                .build();

        // 3. Start analysis
        com.yeonam.tester.dto.AnalysisJobResponse response = analysisService.startAnalysis("KEY-PRJ", request);

        // 4. Verification
        assertNotNull(response.getAnalysisId());
        
        // Retrieve and check the job
        com.yeonam.tester.domain.AnalysisJob job = analysisJobRepository.findById(response.getAnalysisId()).orElse(null);
        assertNotNull(job);
        assertNotNull(job.getStatus());
    }

    @Test
    @Transactional
    void testGenerateReportWithMultiJobTestCases() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("MULTI-JOB-PRJ")
                .name("Multi Job Test Project")
                .githubUrl("https://github.com/test/multi-job")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create 2 AnalysisJobs
        com.yeonam.tester.domain.AnalysisJob job1 = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("JOB1-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt 1")
                .status("COMPLETED")
                .build();
        com.yeonam.tester.domain.AnalysisJob job2 = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("JOB2-ANL")
                .project(project)
                .qaPerspective("PERFORMANCE")
                .customPrompt("Prompt 2")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job1);
        analysisJobRepository.save(job2);

        // 3. Create Requirements for each job
        com.yeonam.tester.domain.Requirement req1 = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("REQ-JOB1")
                .analysisJob(job1)
                .requirementText("Requirement 1")
                .build();
        com.yeonam.tester.domain.Requirement req2 = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("REQ-JOB2")
                .analysisJob(job2)
                .requirementText("Requirement 2")
                .build();
        requirementRepository.save(req1);
        requirementRepository.save(req2);

        // 4. Create TestCases under different jobs
        com.yeonam.tester.domain.TestCase tc1 = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("TC-JOB1-001")
                .analysisJob(job1)
                .requirement(req1)
                .testCaseName("Test Case Job 1")
                .testScenario("Scenario 1")
                .precondition("Precondition 1")
                .testSteps("1. Step 1")
                .expectedResult("Expected 1")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .build();
        com.yeonam.tester.domain.TestCase tc2 = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("TC-JOB2-002")
                .analysisJob(job2)
                .requirement(req2)
                .testCaseName("Test Case Job 2")
                .testScenario("Scenario 2")
                .precondition("Precondition 2")
                .testSteps("1. Step 1")
                .expectedResult("Expected 2")
                .priority("MEDIUM")
                .confidenceLevel("HIGH")
                .build();
        testCaseRepository.save(tc1);
        testCaseRepository.save(tc2);

        // 5. Prepare ReportCreateRequest with representative analysisId = "JOB1-ANL" and both TC IDs
        com.yeonam.tester.dto.ReportCreateRequest request = com.yeonam.tester.dto.ReportCreateRequest.builder()
                .reportFormat("MARKDOWN")
                .testCaseIds(java.util.Arrays.asList("TC-JOB1-001", "TC-JOB2-002"))
                .build();

        // 6. Generate report
        com.yeonam.tester.dto.ReportResponse response = reportService.generateReport("JOB1-ANL", request);

        // 7. Verification
        assertNotNull(response.getReportId());
        assertEquals("DONE", response.getStatus());

        com.yeonam.tester.domain.Report savedReport = reportRepository.findById(response.getReportId()).orElse(null);
        assertNotNull(savedReport);

        // Check mapping table records - should be exactly 2 mappings
        java.util.List<com.yeonam.tester.domain.ReportTestCase> mappings = savedReport.getReportTestCases();
        assertEquals(2, mappings.size());

        // Verify report contains both test cases in mapping
        java.util.Set<String> mappedTcIds = mappings.stream()
                .map(m -> m.getTestCase().getTestCaseId())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(mappedTcIds.contains("TC-JOB1-001"));
        assertTrue(mappedTcIds.contains("TC-JOB2-002"));
    }

    @Test
    @Transactional
    void testDeleteAnalysisJobCascades() {
        // 1. Create a Project
        com.yeonam.tester.domain.Project project = com.yeonam.tester.domain.Project.builder()
                .projectId("DEL-CASCADE-PRJ")
                .name("Delete Cascade Test Project")
                .githubUrl("https://github.com/test/del-cascade")
                .githubBranch("main")
                .integrationStatus("SUCCESS")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.save(project);

        // 2. Create AnalysisJob
        com.yeonam.tester.domain.AnalysisJob job = com.yeonam.tester.domain.AnalysisJob.builder()
                .analysisId("DEL-CASCADE-ANL")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Prompt")
                .status("COMPLETED")
                .build();
        analysisJobRepository.save(job);

        // 3. Create Requirement
        com.yeonam.tester.domain.Requirement req = com.yeonam.tester.domain.Requirement.builder()
                .requirementId("DEL-CASCADE-REQ")
                .analysisJob(job)
                .requirementText("Requirement text")
                .build();
        requirementRepository.save(req);

        // 4. Create TestCase
        com.yeonam.tester.domain.TestCase tc = com.yeonam.tester.domain.TestCase.builder()
                .testCaseId("DEL-CASCADE-TC")
                .analysisJob(job)
                .requirement(req)
                .testCaseName("Test Case Name")
                .testScenario("Scenario")
                .precondition("Precondition")
                .testSteps("1. Step 1")
                .expectedResult("Expected")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .build();
        testCaseRepository.save(tc);

        // 5. Create Evidence & RiskItem
        com.yeonam.tester.domain.Evidence ev = com.yeonam.tester.domain.Evidence.builder()
                .evidenceId("DEL-CASCADE-EV")
                .testCase(tc)
                .chunkId("chunk-1")
                .evidenceText("Evidence text")
                .sourceName("doc.pdf")
                .sourceSection("Section 1")
                .build();
        evidenceRepository.save(ev);

        com.yeonam.tester.domain.RiskItem risk = com.yeonam.tester.domain.RiskItem.builder()
                .riskId("DEL-CASCADE-RISK")
                .testCase(tc)
                .riskType("Security")
                .build();
        riskItemRepository.save(risk);

        // 6. Create Report
        com.yeonam.tester.domain.Report report = com.yeonam.tester.domain.Report.builder()
                .reportId("DEL-CASCADE-RPT")
                .analysisJob(job)
                .s3Path("reports/DEL-CASCADE-ANL/DEL-CASCADE-RPT.md")
                .format("MARKDOWN")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        reportRepository.save(report);

        // 7. Perform delete via service
        analysisService.deleteAnalysisJob("DEL-CASCADE-ANL");

        // 8. Verify cascade deletion
        assertFalse(analysisJobRepository.existsById("DEL-CASCADE-ANL"));
        assertFalse(requirementRepository.existsById("DEL-CASCADE-REQ"));
        assertFalse(testCaseRepository.existsById("DEL-CASCADE-TC"));
        assertFalse(evidenceRepository.existsById("DEL-CASCADE-EV"));
        assertFalse(riskItemRepository.existsById("DEL-CASCADE-RISK"));
        assertFalse(reportRepository.existsById("DEL-CASCADE-RPT"));
    }
}

