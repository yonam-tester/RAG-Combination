package com.yeonam.tester.controller;

import com.yeonam.tester.dto.AnalysisResultResponse;
import com.yeonam.tester.dto.TestCaseUpdateRequest;
import com.yeonam.tester.service.TestCaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    @GetMapping("/projects/{projectId}/testcases")
    public ResponseEntity<List<AnalysisResultResponse.TestCaseDto>> getTestCasesByProject(@PathVariable String projectId) {
        List<AnalysisResultResponse.TestCaseDto> list = testCaseService.getTestCasesByProject(projectId);
        return ResponseEntity.ok(list);
    }

    @PutMapping("/testcases/{testCaseId}")
    public ResponseEntity<AnalysisResultResponse.TestCaseDto> updateTestCase(@PathVariable String testCaseId,
                                                                             @RequestBody TestCaseUpdateRequest request) {
        AnalysisResultResponse.TestCaseDto updated = testCaseService.updateTestCase(testCaseId, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/testcases/{testCaseId}")
    public ResponseEntity<Void> deleteTestCase(@PathVariable String testCaseId) {
        testCaseService.deleteTestCase(testCaseId);
        return ResponseEntity.noContent().build();
    }
}
