package com.yeonam.tester;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.*;
import com.yeonam.tester.repository.*;
import com.yeonam.tester.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Phase8Tests {

    @Autowired
    private FallbackHandler fallbackHandler;

    @Autowired
    private CallbackService callbackService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private FileService fileService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UploadedFileRepository fileRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Test
    void testHallucinationDefenseAndFiltering() {
        // 1. Test isHallucinated method in FallbackHandler
        assertTrue(fallbackHandler.isHallucinated(null, "some text"));
        assertTrue(fallbackHandler.isHallucinated("", "some text"));
        assertTrue(fallbackHandler.isHallucinated("HALLUCINATED_123", "some text"));
        assertTrue(fallbackHandler.isHallucinated("CHK-001", null));
        assertTrue(fallbackHandler.isHallucinated("CHK-002", ""));
        assertFalse(fallbackHandler.isHallucinated("CHK-003", "Valid evidence text"));

        // 2. Test filterEvidences
        List<AnalysisCallbackRequest.EvidenceDto> inputList = new ArrayList<>();
        inputList.add(new AnalysisCallbackRequest.EvidenceDto("CHK-001", "Valid text", "doc.pdf", "sec 1", 0.95));
        inputList.add(new AnalysisCallbackRequest.EvidenceDto("HALLUCINATED_002", "Hallucinated", "doc.pdf", "sec 2", 0.5));
        inputList.add(new AnalysisCallbackRequest.EvidenceDto("CHK-003", "", "doc.pdf", "sec 3", 0.7));

        List<AnalysisCallbackRequest.EvidenceDto> filtered = fallbackHandler.filterEvidences(inputList);
        assertEquals(1, filtered.size());
        assertEquals("CHK-001", filtered.get(0).getChunkId());
    }

    @Test
    @Transactional
    void testCallbackEvidenceLimitingAndScorePersistence() {
        // Setup mock Project and AnalysisJob
        Project mockProject = Project.builder()
                .projectId("PRJ-P8-TEST")
                .name("P8 Test Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(mockProject);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P8-TEST")
                .project(mockProject)
                .status("PROCESSING")
                .build();
        analysisJobRepository.saveAndFlush(job);

        // Prepare Callback Payload with multiple evidences (some hallucinated, some high score, total 5 valid)
        List<AnalysisCallbackRequest.TestCaseDto> testCases = new ArrayList<>();
        
        List<AnalysisCallbackRequest.EvidenceDto> evidences = new ArrayList<>();
        evidences.add(new AnalysisCallbackRequest.EvidenceDto("CHK-A", "Rank 4", "doc.pdf", "sec A", 0.5));
        evidences.add(new AnalysisCallbackRequest.EvidenceDto("CHK-B", "Rank 1", "doc.pdf", "sec B", 0.9));
        evidences.add(new AnalysisCallbackRequest.EvidenceDto("HALLUCINATED_C", "Hallucination", "doc.pdf", "sec C", 0.99));
        evidences.add(new AnalysisCallbackRequest.EvidenceDto("CHK-D", "Rank 2", "doc.pdf", "sec D", 0.8));
        evidences.add(new AnalysisCallbackRequest.EvidenceDto("CHK-E", "Rank 3", "doc.pdf", "sec E", 0.7));
        evidences.add(new AnalysisCallbackRequest.EvidenceDto("CHK-F", "Rank 5", "doc.pdf", "sec F", 0.4));

        AnalysisCallbackRequest.TestCaseDto tcDto = AnalysisCallbackRequest.TestCaseDto.builder()
                .testCaseId("TC-P8-001")
                .requirementId("REQ-P8-001")
                .requirementText("Req text")
                .testCaseName("TC Name")
                .testScenario("Scenario")
                .precondition("Precond")
                .testSteps(Arrays.asList("step 1", "step 2"))
                .expectedResult("Expected")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .riskTags(Arrays.asList("security"))
                .evidences(evidences)
                .build();
        testCases.add(tcDto);

        AnalysisCallbackRequest callbackRequest = AnalysisCallbackRequest.builder()
                .summary("Summary")
                .testCases(testCases)
                .status("COMPLETED")
                .build();

        // Trigger callback service
        callbackService.processCallback("ANL-P8-TEST", callbackRequest);

        // Verify that TC-P8-001 has exactly 3 evidence mappings (since maximum is 3)
        List<Evidence> savedEvidences = evidenceRepository.findByTestCase_TestCaseIdIn(Arrays.asList("TC-P8-001"));
        assertEquals(3, savedEvidences.size());

        // Verify that the saved evidences are the top 3 scores: Rank 1 (0.9), Rank 2 (0.8), Rank 3 (0.7)
        // and that score is persisted
        List<String> chunkIds = savedEvidences.stream().map(Evidence::getChunkId).toList();
        assertTrue(chunkIds.contains("CHK-B"), "Should contain CHK-B (0.9)");
        assertTrue(chunkIds.contains("CHK-D"), "Should contain CHK-D (0.8)");
        assertTrue(chunkIds.contains("CHK-E"), "Should contain CHK-E (0.7)");
        assertFalse(chunkIds.contains("CHK-A"), "Should not contain CHK-A (0.5)");
        assertFalse(chunkIds.contains("CHK-F"), "Should not contain CHK-F (0.4)");
        assertFalse(chunkIds.contains("HALLUCINATED_C"), "Should filter out hallucinated items");

        // Verify scores are correct
        for (Evidence ev : savedEvidences) {
            assertNotNull(ev.getScore());
            assertTrue(ev.getScore() >= 0.7);
        }
    }

    @Test
    @Transactional
    void testDynamicQaRecommendations() {
        // Setup mock Project
        Project mockProject = Project.builder()
                .projectId("PRJ-REC-TEST")
                .name("Recommendations Test Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(mockProject);

        // 1. Recommendations with empty files list (should return fallback API, BACKEND)
        QaRecommendationResponse emptyRec = analysisService.getQaRecommendations("PRJ-REC-TEST");
        assertNotNull(emptyRec);
        assertTrue(emptyRec.getRecommendedPerspectives().contains("API"));
        assertTrue(emptyRec.getRecommendedPerspectives().contains("BACKEND"));
        assertTrue(emptyRec.getReason().contains("기본 QA 관점"));

        // 2. Recommendations with files matching keywords
        UploadedFile securityDoc = UploadedFile.builder()
                .fileId("DOC-REC-1")
                .project(mockProject)
                .fileName("User_Auth_Specification.pdf")
                .fileType("REQUIREMENT_SPEC")
                .status("DONE")
                .s3Path("path/1")
                .build();
        fileRepository.save(securityDoc);

        UploadedFile performanceDoc = UploadedFile.builder()
                .fileId("DOC-REC-2")
                .project(mockProject)
                .fileName("Latency_Load_Test_Plan.docx")
                .fileType("REQUIREMENT_SPEC")
                .status("DONE")
                .s3Path("path/2")
                .build();
        fileRepository.save(performanceDoc);

        QaRecommendationResponse matchedRec = analysisService.getQaRecommendations("PRJ-REC-TEST");
        assertNotNull(matchedRec);
        assertTrue(matchedRec.getRecommendedPerspectives().contains("SECURITY"));
        assertTrue(matchedRec.getRecommendedPerspectives().contains("PERFORMANCE"));
        assertTrue(matchedRec.getReason().contains("보안/인증"));
        assertTrue(matchedRec.getReason().contains("성능/부하"));
    }

    @Test
    @Transactional
    void testRAGVectorDeleteNoExceptionOnOfflineServer() {
        // Prepare mock Project and File
        Project mockProject = Project.builder()
                .projectId("PRJ-VEC-TEST")
                .name("Vector deletion project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        projectRepository.saveAndFlush(mockProject);

        UploadedFile doc = UploadedFile.builder()
                .fileId("DOC-VEC-TEST")
                .project(mockProject)
                .fileName("test.pdf")
                .fileType("REQUIREMENT_SPEC")
                .status("DONE")
                .s3Path("projects/PRJ-VEC-TEST/DOC-VEC-TEST_test.pdf")
                .build();
        fileRepository.saveAndFlush(doc);

        // Try deleting the file. RAG server might be offline, but deleteFile must NOT throw exception
        assertDoesNotThrow(() -> {
            fileService.deleteFile("DOC-VEC-TEST");
        });

        // Ensure database record is deleted
        assertFalse(fileRepository.existsById("DOC-VEC-TEST"));
    }
}
