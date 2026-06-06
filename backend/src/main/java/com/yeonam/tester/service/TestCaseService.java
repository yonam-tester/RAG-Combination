package com.yeonam.tester.service;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.AnalysisResultResponse;
import com.yeonam.tester.dto.TestCaseUpdateRequest;
import com.yeonam.tester.repository.EvidenceRepository;
import com.yeonam.tester.repository.RiskItemRepository;
import com.yeonam.tester.repository.TestCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TestCaseService {

    private final TestCaseRepository testCaseRepository;
    private final RiskItemRepository riskItemRepository;
    private final EvidenceRepository evidenceRepository;

    public TestCaseService(TestCaseRepository testCaseRepository,
                           RiskItemRepository riskItemRepository,
                           EvidenceRepository evidenceRepository) {
        this.testCaseRepository = testCaseRepository;
        this.riskItemRepository = riskItemRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * Gets all test cases belonging to a project, mapped with their risks and evidences.
     */
    @Transactional(readOnly = true)
    public List<AnalysisResultResponse.TestCaseDto> getTestCasesByProject(String projectId) {
        List<TestCase> testCases = testCaseRepository.findByProjectId(projectId);
        return mapTestCasesToDtos(testCases);
    }

    /**
     * Updates an existing testcase.
     */
    @Transactional
    public AnalysisResultResponse.TestCaseDto updateTestCase(String testCaseId, TestCaseUpdateRequest request) {
        TestCase testCase = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found: " + testCaseId));

        if (request.getTestCaseName() != null) {
            testCase.setTestCaseName(request.getTestCaseName());
        }
        if (request.getTestScenario() != null) {
            testCase.setTestScenario(request.getTestScenario());
        }
        if (request.getPrecondition() != null) {
            testCase.setPrecondition(request.getPrecondition());
        }
        if (request.getTestSteps() != null) {
            testCase.setTestSteps(String.join("\n", request.getTestSteps()));
        }
        if (request.getExpectedResult() != null) {
            testCase.setExpectedResult(request.getExpectedResult());
        }
        if (request.getPriority() != null) {
            testCase.setPriority(request.getPriority());
        }
        if (request.getCategory() != null) {
            testCase.setCategory(request.getCategory());
        }
        if (request.getTechnique() != null) {
            testCase.setTechnique(request.getTechnique());
        }
        if (request.getTddHint() != null) {
            testCase.setTddHint(request.getTddHint());
        }
        if (request.getNegativeScenario() != null) {
            testCase.setNegativeScenario(request.getNegativeScenario());
        }

        TestCase saved = testCaseRepository.save(testCase);
        return mapTestCaseToDto(saved);
    }

    /**
     * Deletes a testcase and all its associated risks and evidences.
     */
    @Transactional
    public void deleteTestCase(String testCaseId) {
        if (!testCaseRepository.existsById(testCaseId)) {
            throw new IllegalArgumentException("Test case not found: " + testCaseId);
        }

        // Delete children first due to referential integrity constraints
        evidenceRepository.deleteByTestCaseIds(Collections.singletonList(testCaseId));
        riskItemRepository.deleteByTestCaseIds(Collections.singletonList(testCaseId));

        // Delete parent
        testCaseRepository.deleteById(testCaseId);
    }

    private List<AnalysisResultResponse.TestCaseDto> mapTestCasesToDtos(List<TestCase> testCases) {
        List<String> tcIds = testCases.stream().map(TestCase::getTestCaseId).collect(Collectors.toList());

        Map<String, List<RiskItem>> risksMap = new HashMap<>();
        Map<String, List<Evidence>> evidencesMap = new HashMap<>();

        if (!tcIds.isEmpty()) {
            List<RiskItem> risks = riskItemRepository.findByTestCase_TestCaseIdIn(tcIds);
            for (RiskItem r : risks) {
                risksMap.computeIfAbsent(r.getTestCase().getTestCaseId(), k -> new ArrayList<>()).add(r);
            }

            List<Evidence> evidences = evidenceRepository.findByTestCase_TestCaseIdIn(tcIds);
            for (Evidence e : evidences) {
                evidencesMap.computeIfAbsent(e.getTestCase().getTestCaseId(), k -> new ArrayList<>()).add(e);
            }
        }

        return testCases.stream().map(tc -> {
            List<String> riskTags = risksMap.getOrDefault(tc.getTestCaseId(), Collections.emptyList())
                    .stream().map(r -> r.getRiskType().startsWith("#") ? r.getRiskType() : "#" + r.getRiskType())
                    .collect(Collectors.toList());

            List<AnalysisResultResponse.EvidenceDto> evDtos = evidencesMap.getOrDefault(tc.getTestCaseId(), Collections.emptyList())
                    .stream().map(ev -> AnalysisResultResponse.EvidenceDto.builder()
                            .evidenceId(ev.getEvidenceId())
                            .evidenceText(ev.getEvidenceText())
                            .sourceName(ev.getSourceName())
                            .sourceSection(ev.getSourceSection())
                            .confidenceLevel(tc.getConfidenceLevel() != null ? tc.getConfidenceLevel() : "HIGH")
                            .build())
                    .collect(Collectors.toList());

            List<String> stepsList = new ArrayList<>();
            if (tc.getTestSteps() != null) {
                String[] lines = tc.getTestSteps().split("\n");
                for (String l : lines) {
                    if (!l.trim().isEmpty()) {
                        stepsList.add(l.trim());
                    }
                }
            }

            return AnalysisResultResponse.TestCaseDto.builder()
                    .testCaseId(tc.getTestCaseId())
                    .testCaseName(tc.getTestCaseName())
                    .testScenario(tc.getTestScenario())
                    .precondition(tc.getPrecondition())
                    .testSteps(stepsList)
                    .expectedResult(tc.getExpectedResult())
                    .priority(tc.getPriority())
                    .riskTags(riskTags)
                    .relatedRequirements(Collections.singletonList(tc.getRequirement().getRequirementId()))
                    .evidences(evDtos)
                    .category(tc.getCategory())
                    .technique(tc.getTechnique())
                    .tddHint(tc.getTddHint())
                    .negativeScenario(tc.getNegativeScenario())
                    .analysisId(tc.getAnalysisJob().getAnalysisId())
                    .build();
        }).collect(Collectors.toList());
    }

    private AnalysisResultResponse.TestCaseDto mapTestCaseToDto(TestCase tc) {
        return mapTestCasesToDtos(Collections.singletonList(tc)).get(0);
    }
}
