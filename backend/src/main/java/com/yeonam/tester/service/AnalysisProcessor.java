package com.yeonam.tester.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.UploadedFile;
import com.yeonam.tester.dto.AnalysisCallbackRequest;
import com.yeonam.tester.llm.LlmClient;
import com.yeonam.tester.repository.AnalysisJobRepository;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.List;

@Service
public class AnalysisProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisProcessor.class);

    private final FileService fileService;
    private final LlmClient llmClient;
    private final CallbackService callbackService;
    private final AnalysisJobRepository analysisJobRepository;
    private final ObjectMapper objectMapper;
    private final Tika tika;

    public AnalysisProcessor(FileService fileService,
                             LlmClient llmClient,
                             CallbackService callbackService,
                             AnalysisJobRepository analysisJobRepository) {
        this.fileService = fileService;
        this.llmClient = llmClient;
        this.callbackService = callbackService;
        this.analysisJobRepository = analysisJobRepository;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.tika = new Tika();
    }

    /**
     * S3 문서 다운로드 -> Tika 텍스트 추출 -> LLM 호출 -> Callback 저장을 비동기로 처리합니다.
     */
    @Async
    @Transactional
    public void processDirectly(String analysisId, List<String> fileIds, String mergedCustomPrompt, String qaPerspective) {
        log.info("Starting direct analysis processing in background for job: {}", analysisId);

        // 1. Update job status to PROCESSING
        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));
        job.setStatus("PROCESSING");
        analysisJobRepository.save(job);

        try {
            // 2. Extract text from target files using Apache Tika
            StringBuilder requirementsBuilder = new StringBuilder();
            for (String fileId : fileIds) {
                try {
                    UploadedFile file = fileService.getFileById(fileId);
                    byte[] fileBytes = fileService.downloadFileBytes(file);
                    
                    log.info("Extracting text from file: {} (size: {} bytes) for job: {}", 
                            file.getFileName(), fileBytes.length, analysisId);
                    
                    String fileText = tika.parseToString(new ByteArrayInputStream(fileBytes));
                    if (fileText != null && !fileText.isBlank()) {
                        requirementsBuilder.append("\n--- Document: ").append(file.getFileName()).append(" ---\n");
                        requirementsBuilder.append(fileText);
                    }
                } catch (Exception e) {
                    log.error("Failed to extract text from fileId: {} for job: {}", fileId, analysisId, e);
                    requirementsBuilder.append("\n--- Document Error (ID: ").append(fileId).append(") ---\n");
                    requirementsBuilder.append("[텍스트 추출 실패: ").append(e.getMessage()).append("]\n");
                }
            }

            String requirementText = requirementsBuilder.toString().trim();
            if (requirementText.isEmpty()) {
                throw new IllegalStateException("분석 대상 문서들로부터 요구사항 텍스트를 추출하지 못했습니다.");
            }

            // 3. Generate test cases via LLM Client (Mock / Bedrock)
            log.info("Requesting LLM analysis for job: {}", analysisId);
            String llmResponse = llmClient.generateTestCases(requirementText, mergedCustomPrompt, qaPerspective);
            log.info("LLM response generated successfully for job: {}", analysisId);

            // 4. Parse LLM JSON response to AnalysisCallbackRequest DTO
            AnalysisCallbackRequest callbackRequest = parseLlmResponse(llmResponse);
            callbackRequest.setStatus("COMPLETED");

            // 5. Invoke CallbackService to save analysis outcomes
            log.info("Invoking CallbackService.processCallback for job: {}", analysisId);
            callbackService.processCallback(analysisId, callbackRequest);
            log.info("Analysis job completed successfully for job: {}", analysisId);

        } catch (Exception e) {
            log.error("Direct analysis processing failed for job: {}", analysisId, e);
            // Handle analysis failure by triggering FAILED callback
            AnalysisCallbackRequest failedRequest = AnalysisCallbackRequest.builder()
                    .status("FAILED")
                    .errorMessage("백엔드 LLM 처리 중 오류가 발생했습니다: " + e.getMessage())
                    .build();
            try {
                callbackService.processCallback(analysisId, failedRequest);
            } catch (Exception callbackEx) {
                log.error("Failed to process FAILED status callback for job: {}", analysisId, callbackEx);
            }
        }
    }

    private AnalysisCallbackRequest parseLlmResponse(String response) throws Exception {
        String cleaned = response.trim();
        // Remove markdown JSON formatting block if present
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            return objectMapper.readValue(cleaned, AnalysisCallbackRequest.class);
        } catch (Exception e) {
            log.error("Jackson deserialization failed. Cleaned response payload:\n{}", cleaned);
            throw new IllegalArgumentException("LLM 응답을 JSON DTO로 역직렬화하는 데 실패했습니다: " + e.getMessage(), e);
        }
    }
}
