package com.yeonam.tester.service;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.AnalysisCallbackRequest;
import com.yeonam.tester.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final RequirementRepository requirementRepository;
    private final TestCaseRepository testCaseRepository;
    private final RiskItemRepository riskItemRepository;
    private final EvidenceRepository evidenceRepository;
    private final PriorityEvaluator priorityEvaluator;
    private final RiskDetector riskDetector;
    private final FallbackHandler fallbackHandler;

    public CallbackService(AnalysisJobRepository analysisJobRepository,
                           RequirementRepository requirementRepository,
                           TestCaseRepository testCaseRepository,
                           RiskItemRepository riskItemRepository,
                           EvidenceRepository evidenceRepository,
                           PriorityEvaluator priorityEvaluator,
                           RiskDetector riskDetector,
                           FallbackHandler fallbackHandler) {
        this.analysisJobRepository = analysisJobRepository;
        this.requirementRepository = requirementRepository;
        this.testCaseRepository = testCaseRepository;
        this.riskItemRepository = riskItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.priorityEvaluator = priorityEvaluator;
        this.riskDetector = riskDetector;
        this.fallbackHandler = fallbackHandler;
    }

    /**
     * Webhook callback to store analysis results and update job status to COMPLETED.
     */
    @Transactional
    public void processCallback(String analysisId, AnalysisCallbackRequest request) {
        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));

        // Handle failure callback
        if ("FAILED".equals(request.getStatus())) {
            log.error("Analysis job failed callback received for ID {}: {}", analysisId, request.getErrorMessage());
            job.setStatus("FAILED");
            job.setSummary(request.getErrorMessage() != null ? request.getErrorMessage() : "분석 실패");
            analysisJobRepository.save(job);
            return;
        }

        // 1. Update Job Summary & Status & Missing Items
        job.setSummary(request.getSummary());
        job.setStatus("COMPLETED");

        if (request.getMissingItems() != null && !request.getMissingItems().isEmpty()) {
            job.setMissingItemsText(String.join(";", request.getMissingItems()));
        } else {
            job.setMissingItemsText("");
        }
        analysisJobRepository.save(job);

        if (request.getTestCases() == null || request.getTestCases().isEmpty()) {
            log.warn("Callback contains empty test cases for analysis: {}", analysisId);
            return;
        }

        // 2. Iterate and persist Requirements, TestCases, Risks and Evidences
        for (AnalysisCallbackRequest.TestCaseDto tcDto : request.getTestCases()) {
            // A. Save Requirement
            String reqId = tcDto.getRequirementId();
            if (reqId == null || reqId.isBlank()) {
                reqId = "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            }

            String reqText = tcDto.getRequirementText();
            if (reqText == null || reqText.isBlank()) {
                reqText = tcDto.getTestCaseName() + "에 해당하는 요구사항 정의";
            }

            Requirement requirement = Requirement.builder()
                    .requirementId(reqId)
                    .analysisJob(job)
                    .requirementText(reqText)
                    .build();

            // Check if already exists in DB to prevent duplicates in multi-callbacks
            if (!requirementRepository.existsById(reqId)) {
                requirementRepository.save(requirement);
            } else {
                requirement = requirementRepository.findById(reqId).orElse(requirement);
            }

            // B. Evaluate Priority & Risks
            String finalPriority = tcDto.getPriority();
            if (finalPriority == null || finalPriority.isBlank()) {
                finalPriority = priorityEvaluator.evaluatePriority(reqText, tcDto.getTestScenario());
            }

            // C. Build TestCase steps text (delimited by newline for CLOB mapping)
            String stepsStr = "";
            if (tcDto.getTestSteps() != null) {
                stepsStr = String.join("\n", tcDto.getTestSteps());
            }

            // D. Save TestCase
            String tcId = tcDto.getTestCaseId();
            if (tcId == null || tcId.isBlank()) {
                tcId = "TC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            }

            TestCase testCase = TestCase.builder()
                    .testCaseId(tcId)
                    .analysisJob(job)
                    .requirement(requirement)
                    .testCaseName(tcDto.getTestCaseName())
                    .testScenario(tcDto.getTestScenario())
                    .precondition(tcDto.getPrecondition())
                    .testSteps(stepsStr)
                    .expectedResult(tcDto.getExpectedResult())
                    .priority(finalPriority)
                    .confidenceLevel(tcDto.getConfidenceLevel() != null ? tcDto.getConfidenceLevel() : "HIGH")
                    .category(tcDto.getCategory())
                    .technique(tcDto.getTechnique())
                    .tddHint(tcDto.getTddHint())
                    .negativeScenario(tcDto.getNegativeScenario())
                    .caution(tcDto.getCaution())
                    .build();

            testCaseRepository.save(testCase);

            // E. Process Risk Tags
            List<String> riskTags = tcDto.getRiskTags();
            if (riskTags == null || riskTags.isEmpty()) {
                riskTags = riskDetector.detectRisks(reqText, tcDto.getTestScenario());
            }

            for (String tag : riskTags) {
                RiskItem risk = RiskItem.builder()
                        .riskId("RSK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .testCase(testCase)
                        .riskType(tag)
                        .build();
                riskItemRepository.save(risk);
            }

            // F. Process Evidences with Hallucination Defense check
            if (tcDto.getEvidences() != null) {
                List<AnalysisCallbackRequest.EvidenceDto> validEvidences = fallbackHandler.filterEvidences(tcDto.getEvidences());
                
                // Sort by score descending (null scores treated as 0.0)
                validEvidences.sort((a, b) -> {
                    double scoreA = a.getScore() != null ? a.getScore() : 0.0;
                    double scoreB = b.getScore() != null ? b.getScore() : 0.0;
                    return Double.compare(scoreB, scoreA); // Descending
                });
                
                // Limit to maximum 3 evidences (Task 8.2 requirement)
                int limit = Math.min(validEvidences.size(), 3);
                for (int i = 0; i < limit; i++) {
                    AnalysisCallbackRequest.EvidenceDto evDto = validEvidences.get(i);
                    Evidence evidence = Evidence.builder()
                            .evidenceId("EVI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                            .testCase(testCase)
                            .chunkId(evDto.getChunkId())
                            .evidenceText(evDto.getEvidenceText())
                            .sourceName(evDto.getSourceName() != null ? evDto.getSourceName() : "Unknown Source")
                            .sourceSection(evDto.getSourceSection())
                            .score(evDto.getScore())
                            .build();

                    evidenceRepository.save(evidence);
                }
            }
        }
    }
}
