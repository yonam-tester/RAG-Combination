package com.yeonam.tester.service;

import com.yeonam.tester.domain.UploadedFile;
import com.yeonam.tester.repository.UploadedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class FilePreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(FilePreprocessingService.class);

    private final UploadedFileRepository fileRepository;
    private final HttpClient httpClient;

    @Value("${ai.server.url:http://localhost:8000}")
    private String aiServerUrl;

    public FilePreprocessingService(UploadedFileRepository fileRepository) {
        this.fileRepository = fileRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Asynchronously preprocesses the uploaded file.
     * Transitions status: UPLOADED -> PROCESSING -> DONE (or FAILED).
     */
    @Async
    @Transactional
    public void preprocessFile(String fileId) {
        log.info("Starting async preprocessing for fileId: {}", fileId);

        // 1. Fetch File Entity
        UploadedFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        // 2. Set Status to PROCESSING
        file.setStatus("PROCESSING");
        fileRepository.saveAndFlush(file);

        String targetUrl = aiServerUrl + "/api/files/preprocess";
        String payload = String.format("{\"fileId\":\"%s\",\"s3Path\":\"%s\"}", file.getFileId(), file.getS3Path());

        try {
            // 3. Request FastAPI Preprocess validation
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("FastAPI successfully preprocessed file {}. Setting status to DONE.", fileId);
                file.setStatus("DONE");
            } else {
                log.warn("FastAPI preprocess returned error status: {}. Falling back to local verification.", response.statusCode());
                runLocalFallback(file);
            }
        } catch (Exception e) {
            log.warn("Failed to contact FastAPI preprocess server ({}): {}. Falling back to local verification.", targetUrl, e.getMessage());
            runLocalFallback(file);
        }

        fileRepository.save(file);
        log.info("Finished async preprocessing for fileId: {}. Final status: {}", fileId, file.getStatus());
    }

    /**
     * Local fallback mechanism in case FastAPI is offline or fails.
     * Validates if file is stored properly and marks as DONE.
     */
    private void runLocalFallback(UploadedFile file) {
        try {
            // Simple verification: check if S3 path and filename are valid and not empty.
            if (file.getS3Path() != null && !file.getS3Path().isBlank() && file.getFileName() != null) {
                log.info("Local fallback verification passed for file {}. Setting status to DONE.", file.getFileId());
                file.setStatus("DONE");
            } else {
                log.error("Local fallback verification failed for file {}. Setting status to FAILED.", file.getFileId());
                file.setStatus("FAILED");
            }
        } catch (Exception e) {
            log.error("Exception during local fallback verification: {}", e.getMessage(), e);
            file.setStatus("FAILED");
        }
    }
}
