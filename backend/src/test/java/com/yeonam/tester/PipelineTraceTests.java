package com.yeonam.tester;

import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.Project;
import com.yeonam.tester.dto.AnalysisCallbackRequest;
import com.yeonam.tester.dto.AnalysisResultResponse;
import com.yeonam.tester.repository.AnalysisJobRepository;
import com.yeonam.tester.repository.ProjectRepository;
import com.yeonam.tester.service.AnalysisService;
import com.yeonam.tester.service.CallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PipelineTraceTests {

    @Autowired
    private CallbackService callbackService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private static final String TEST_PROJECT_ID = "PRJ-TRACE-TEST";
    private static final String TEST_ANALYSIS_ID = "ANL-TRACE-TEST-001";

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteById(TEST_ANALYSIS_ID);
        projectRepository.deleteById(TEST_PROJECT_ID);

        Project project = Project.builder()
                .projectId(TEST_PROJECT_ID)
                .name("Trace Test Project")
                .description("For pipeline trace tests")
                .createdAt(LocalDateTime.now())
                .build();
        projectRepository.save(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId(TEST_ANALYSIS_ID)
                .project(project)
                .status("WAITING")
                .build();
        analysisJobRepository.save(job);
    }

    @Test
    void pipelineTrace_savedAndRetrievedCorrectly() {
        List<Map<String, Object>> trace = List.of(
                Map.of("step", "PARSE",    "status", "SUCCESS", "fileCount", 2, "durationMs", 1200),
                Map.of("step", "CHUNK",    "status", "SUCCESS", "chunkCount", 47, "durationMs", 300),
                Map.of("step", "INDEX",    "status", "SUCCESS", "vectorCount", 47, "durationMs", 800),
                Map.of("step", "EXTRACT",  "status", "SUCCESS", "reqCount", 8, "durationMs", 2100),
                Map.of("step", "RETRIEVE", "status", "SUCCESS", "topScore", 0.87, "durationMs", 95)
        );

        AnalysisCallbackRequest req = AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("Test summary")
                .testCases(Collections.emptyList())
                .missingItems(Collections.emptyList())
                .pipelineTrace(trace)
                .build();

        callbackService.processCallback(TEST_ANALYSIS_ID, req);

        AnalysisJob saved = analysisJobRepository.findById(TEST_ANALYSIS_ID).orElseThrow();
        assertNotNull(saved.getPipelineTrace(), "pipeline_trace 컬럼에 값이 저장되어야 한다");
        assertTrue(saved.getPipelineTrace().contains("PARSE"), "저장된 JSON에 PARSE 단계가 포함되어야 한다");
        assertTrue(saved.getPipelineTrace().contains("SUCCESS"), "저장된 JSON에 SUCCESS 상태가 포함되어야 한다");

        AnalysisResultResponse result = analysisService.getAnalysisResults(TEST_ANALYSIS_ID);
        assertNotNull(result.getPipelineTrace(), "결과 응답에 pipelineTrace가 포함되어야 한다");
        assertEquals(5, result.getPipelineTrace().size(), "5단계 trace가 모두 포함되어야 한다");
        assertEquals("PARSE", result.getPipelineTrace().get(0).get("step"));
        assertEquals("SUCCESS", result.getPipelineTrace().get(0).get("status"));
    }

    @Test
    void pipelineTrace_mockMode_allSkipped() {
        List<Map<String, Object>> mockTrace = List.of(
                Map.of("step", "PARSE",    "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "CHUNK",    "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "INDEX",    "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "EXTRACT",  "status", "SKIPPED", "durationMs", 0),
                Map.of("step", "RETRIEVE", "status", "SKIPPED", "durationMs", 0)
        );

        AnalysisCallbackRequest req = AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("Mock summary")
                .testCases(Collections.emptyList())
                .missingItems(Collections.emptyList())
                .pipelineTrace(mockTrace)
                .build();

        callbackService.processCallback(TEST_ANALYSIS_ID, req);

        AnalysisResultResponse result = analysisService.getAnalysisResults(TEST_ANALYSIS_ID);
        assertNotNull(result.getPipelineTrace());
        assertEquals(5, result.getPipelineTrace().size());
        assertTrue(result.getPipelineTrace().stream()
                .allMatch(step -> "SKIPPED".equals(step.get("status"))),
                "Mock 모드에서는 모든 단계가 SKIPPED여야 한다");
    }

    @Test
    void pipelineTrace_nullWhenNotProvided() {
        AnalysisCallbackRequest req = AnalysisCallbackRequest.builder()
                .status("COMPLETED")
                .summary("No trace summary")
                .testCases(Collections.emptyList())
                .missingItems(Collections.emptyList())
                .build();

        callbackService.processCallback(TEST_ANALYSIS_ID, req);

        AnalysisResultResponse result = analysisService.getAnalysisResults(TEST_ANALYSIS_ID);
        assertNotNull(result.getPipelineTrace());
        assertTrue(result.getPipelineTrace().isEmpty(), "trace가 없으면 빈 리스트여야 한다");
    }
}
