package com.yeonam.tester.controller;

import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.dto.*;
import com.yeonam.tester.service.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }



    @PostMapping("/api/projects/{projectId}/analysis")
    public ResponseEntity<?> startAnalysis(@PathVariable String projectId, @RequestBody AnalysisCreateRequest request) {
        try {
            AnalysisJobResponse jobResponse = analysisService.startAnalysis(projectId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(jobResponse);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Analysis failed to start: " + e.getMessage()));
        }
    }

    @GetMapping("/api/analysis/{analysisId}")
    public ResponseEntity<?> getAnalysisJob(@PathVariable String analysisId) {
        try {
            AnalysisJob job = analysisService.getAnalysisEntity(analysisId);
            AnalysisJobResponse response = AnalysisJobResponse.builder()
                    .analysisId(job.getAnalysisId())
                    .projectId(job.getProject().getProjectId())
                    .status(job.getStatus())
                    .createdAt(java.time.LocalDateTime.now())
                    .qaPerspective(job.getQaPerspective())
                    .build();
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/api/analysis/{analysisId}/status")
    public ResponseEntity<?> getAnalysisStatus(@PathVariable String analysisId) {
        try {
            AnalysisStatusResponse status = analysisService.getAnalysisStatus(analysisId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/api/analysis/{analysisId}/results")
    public ResponseEntity<?> getAnalysisResults(@PathVariable String analysisId) {
        try {
            AnalysisResultResponse results = analysisService.getAnalysisResults(analysisId);
            return ResponseEntity.ok(results);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    private static java.time.LocalDateTime LocalDateTimeFormatter(java.time.LocalDateTime dt) {
        return dt != null ? dt : java.time.LocalDateTime.now();
    }

    @GetMapping("/api/projects/{projectId}/analysis")
    public ResponseEntity<?> getAnalysisJobsByProject(@PathVariable String projectId) {
        try {
            List<AnalysisJobResponse> responses = analysisService.getAnalysisJobsByProject(projectId);
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to fetch analysis jobs: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/analysis/{analysisId}")
    public ResponseEntity<?> deleteAnalysisJob(@PathVariable String analysisId) {
        try {
            analysisService.deleteAnalysisJob(analysisId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to delete analysis job: " + e.getMessage()));
        }
    }

    @GetMapping("/api/projects/{projectId}/qa-recommendations")
    public ResponseEntity<?> getQaRecommendations(@PathVariable String projectId) {
        try {
            QaRecommendationResponse recommendations = analysisService.getQaRecommendations(projectId);
            return ResponseEntity.ok(recommendations);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to fetch QA recommendations: " + e.getMessage()));
        }
    }

    private static class ErrorResponse {
        private final String message;
        public ErrorResponse(String message) {
            this.message = message;
        }
        public String getMessage() {
            return message;
        }
    }
}
