package com.yeonam.tester.service;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportAssemblyService {

    private final ProjectRepository projectRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final TestCaseRepository testCaseRepository;
    private final RequirementRepository requirementRepository;
    private final RiskItemRepository riskItemRepository;
    private final EvidenceRepository evidenceRepository;

    public ReportAssemblyService(ProjectRepository projectRepository,
                                 AnalysisJobRepository analysisJobRepository,
                                 TestCaseRepository testCaseRepository,
                                 RequirementRepository requirementRepository,
                                 RiskItemRepository riskItemRepository,
                                 EvidenceRepository evidenceRepository) {
        this.projectRepository = projectRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.testCaseRepository = testCaseRepository;
        this.requirementRepository = requirementRepository;
        this.riskItemRepository = riskItemRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * Gathers all data related to the analysis job and compiles it into a map model.
     */
    public Map<String, Object> assembleReportData(String analysisId) {
        return assembleReportData(analysisId, null);
    }

    public Map<String, Object> assembleReportData(String analysisId, List<String> testCaseIds) {
        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));

        Project project = job.getProject();
        List<TestCase> testCases;
        if (testCaseIds == null || testCaseIds.isEmpty()) {
            testCases = testCaseRepository.findByAnalysisJob_AnalysisId(analysisId);
        } else {
            testCases = testCaseRepository.findAllById(testCaseIds);
        }
        
        List<Requirement> requirements = requirementRepository.findByAnalysisJob_AnalysisId(analysisId);

        List<String> tcIds = new ArrayList<>();
        for (TestCase tc : testCases) {
            tcIds.add(tc.getTestCaseId());
        }

        // Fetch risks and evidences in batch to avoid N+1 queries
        Map<String, List<RiskItem>> risksByTc = new HashMap<>();
        Map<String, List<Evidence>> evidencesByTc = new HashMap<>();

        if (!tcIds.isEmpty()) {
            List<RiskItem> risks = riskItemRepository.findByTestCase_TestCaseIdIn(tcIds);
            for (RiskItem risk : risks) {
                risksByTc.computeIfAbsent(risk.getTestCase().getTestCaseId(), k -> new ArrayList<>()).add(risk);
            }

            List<Evidence> evidences = evidenceRepository.findByTestCase_TestCaseIdIn(tcIds);
            for (Evidence ev : evidences) {
                evidencesByTc.computeIfAbsent(ev.getTestCase().getTestCaseId(), k -> new ArrayList<>()).add(ev);
            }
        }

        Map<String, Object> model = new HashMap<>();
        model.put("project", project);
        model.put("job", job);
        model.put("requirements", requirements);

        List<Map<String, Object>> tcModels = new ArrayList<>();
        for (TestCase tc : testCases) {
            Map<String, Object> tcMap = new HashMap<>();
            tcMap.put("testCaseId", tc.getTestCaseId());
            tcMap.put("testCaseName", tc.getTestCaseName());
            tcMap.put("testScenario", tc.getTestScenario());
            tcMap.put("precondition", tc.getPrecondition());
            tcMap.put("testSteps", tc.getTestSteps()); // raw string/JSON/CLOB
            tcMap.put("expectedResult", tc.getExpectedResult());
            tcMap.put("priority", tc.getPriority());
            tcMap.put("confidenceLevel", tc.getConfidenceLevel());
            tcMap.put("requirementId", tc.getRequirement().getRequirementId());
            tcMap.put("requirementText", tc.getRequirement().getRequirementText());

            List<RiskItem> tcRisks = risksByTc.getOrDefault(tc.getTestCaseId(), Collections.emptyList());
            tcMap.put("risks", tcRisks);

            List<Evidence> tcEvidences = evidencesByTc.getOrDefault(tc.getTestCaseId(), Collections.emptyList());
            tcMap.put("evidences", tcEvidences);

            tcModels.add(tcMap);
        }
        model.put("testCases", tcModels);

        return model;
    }
}
