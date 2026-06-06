package com.yeonam.tester;

import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class AnalysisJobEntityTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.yeonam.tester.service.CallbackService callbackService;

    @Autowired
    private com.yeonam.tester.repository.AnalysisJobRepository analysisJobRepository;

    @Autowired
    private com.yeonam.tester.repository.ProjectRepository projectRepository;

    @Test
    void testAnalysisJobEntityBuilderAndGetters() {
        Project project = Project.builder()
                .projectId("PRJ-TEST-123")
                .name("Test Project")
                .description("Test description")
                .createdAt(LocalDateTime.now())
                .build();

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANA-TEST-123")
                .project(project)
                .qaPerspective("SECURITY")
                .customPrompt("Test custom prompt")
                .summary("Test summary")
                .status("WAITING")
                .missingItemsText("로그아웃 이후의 세션 만료 처리에 대한 명세가 누락됨;결제 타임아웃 상황 처리 누락")
                .build();

        assertEquals("ANA-TEST-123", job.getAnalysisId());
        assertEquals(project, job.getProject());
        assertEquals("SECURITY", job.getQaPerspective());
        assertEquals("Test custom prompt", job.getCustomPrompt());
        assertEquals("Test summary", job.getSummary());
        assertEquals("WAITING", job.getStatus());
        assertEquals("로그아웃 이후의 세션 만료 처리에 대한 명세가 누락됨;결제 타임아웃 상황 처리 누락", job.getMissingItemsText());
    }

    @Test
    void verifyAnalysisJobTableHasMissingItemsTextColumn() {
        // Query H2 columns schema to check if MISSING_ITEMS_TEXT column exists in ANALYSIS_JOB
        String checkColumnQuery = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'ANALYSIS_JOB' AND COLUMN_NAME = 'MISSING_ITEMS_TEXT'";
        Integer count = jdbcTemplate.queryForObject(checkColumnQuery, Integer.class);
        
        assertNotNull(count);
        assertTrue(count > 0, "Column MISSING_ITEMS_TEXT should exist in table ANALYSIS_JOB");
    }

    @Test
    void testFailedCallbackUpdatesStatusAndSummary() {
        // Given
        Project project = Project.builder()
                .projectId("PRJ-FAIL-CALLBACK")
                .name("Fail Callback Test Project")
                .createdAt(LocalDateTime.now())
                .build();
        projectRepository.save(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-FAIL-CALLBACK")
                .project(project)
                .status("WAITING")
                .build();
        analysisJobRepository.save(job);

        com.yeonam.tester.dto.AnalysisCallbackRequest request = com.yeonam.tester.dto.AnalysisCallbackRequest.builder()
                .status("FAILED")
                .errorMessage("API 키 유효성 검증 실패: 유효하지 않은 API 키이거나 만료되었습니다. 키 설정을 재점검해 주세요.")
                .build();

        // When
        callbackService.processCallback("ANL-FAIL-CALLBACK", request);

        // Then
        AnalysisJob updatedJob = analysisJobRepository.findById("ANL-FAIL-CALLBACK").orElseThrow();
        assertEquals("FAILED", updatedJob.getStatus());
        assertEquals("API 키 유효성 검증 실패: 유효하지 않은 API 키이거나 만료되었습니다. 키 설정을 재점검해 주세요.", updatedJob.getSummary());
    }

    @Test
    void testCompletedCallbackSavesMissingItemsText() {
        // Given
        Project project = Project.builder()
                .projectId("PRJ-SUCCESS-CALLBACK")
                .name("Success Callback Test Project")
                .createdAt(LocalDateTime.now())
                .build();
        projectRepository.save(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-SUCCESS-CALLBACK")
                .project(project)
                .status("WAITING")
                .build();
        analysisJobRepository.save(job);

        java.util.List<String> missing = java.util.Arrays.asList(
                "로그아웃 이후의 세션 만료 처리에 대한 명세가 누락됨",
                "결제 타임아웃 상황 처리 누락"
        );
        com.yeonam.tester.dto.AnalysisCallbackRequest request = com.yeonam.tester.dto.AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("분석 완료")
                .missingItems(missing)
                .build();

        // When
        callbackService.processCallback("ANL-SUCCESS-CALLBACK", request);

        // Then
        AnalysisJob updatedJob = analysisJobRepository.findById("ANL-SUCCESS-CALLBACK").orElseThrow();
        assertEquals("COMPLETED", updatedJob.getStatus());
        assertEquals("로그아웃 이후의 세션 만료 처리에 대한 명세가 누락됨;결제 타임아웃 상황 처리 누락", updatedJob.getMissingItemsText());
    }
}
