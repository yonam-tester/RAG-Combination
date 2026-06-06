package com.yeonam.tester.service;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.*;
import com.yeonam.tester.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    private final AnalysisJobRepository analysisJobRepository;
    private final ProjectRepository projectRepository;
    private final UploadedFileRepository fileRepository;
    private final TestCaseRepository testCaseRepository;
    private final RequirementRepository requirementRepository;
    private final RiskItemRepository riskItemRepository;
    private final EvidenceRepository evidenceRepository;
    private final HttpClient httpClient;
    private final S3Client s3Client;
    private final ReportRepository reportRepository;

    @Value("${aws.s3.buckets.reports}")
    private String reportsBucket;

    @Value("${ai.server.url:http://localhost:8000}")
    private String aiServerUrl;

    @Value("${llm.direct:false}")
    private boolean llmDirect;

    private final AnalysisProcessor analysisProcessor;

    public AnalysisService(AnalysisJobRepository analysisJobRepository,
                           ProjectRepository projectRepository,
                           UploadedFileRepository fileRepository,
                           TestCaseRepository testCaseRepository,
                           RequirementRepository requirementRepository,
                           RiskItemRepository riskItemRepository,
                           EvidenceRepository evidenceRepository,
                           S3Client s3Client,
                           ReportRepository reportRepository,
                           AnalysisProcessor analysisProcessor) {
        this.analysisJobRepository = analysisJobRepository;
        this.projectRepository = projectRepository;
        this.fileRepository = fileRepository;
        this.testCaseRepository = testCaseRepository;
        this.requirementRepository = requirementRepository;
        this.riskItemRepository = riskItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.s3Client = s3Client;
        this.reportRepository = reportRepository;
        this.analysisProcessor = analysisProcessor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }



    /**
     * Initiates the AI/RAG analysis job on the selected documents.
     */
    @Transactional
    public AnalysisJobResponse startAnalysis(String projectId, AnalysisCreateRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        String analysisId = "ANL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Assemble QA Perspective prompts
        StringBuilder perspectivePrompt = new StringBuilder();
        if (request.getQaPerspectives() != null && !request.getQaPerspectives().isEmpty()) {
            perspectivePrompt.append("[QA 검증 관점 가이드]\n");
            for (String p : request.getQaPerspectives()) {
                switch (p.toUpperCase()) {
                    case "SECURITY":
                        perspectivePrompt.append("- SECURITY: 인증, 인가, 데이터 오용, 입력값 유효성 검증을 위한 침투형 시나리오 수립에 집중하라.\n");
                        break;
                    case "PERFORMANCE":
                        perspectivePrompt.append("- PERFORMANCE: 시스템 부하, 응답 지연, 리소스 병목 현상 및 동시성 처리에 대한 한계 조건 검증에 집중하라.\n");
                        break;
                    case "BACKEND":
                        perspectivePrompt.append("- BACKEND: 서버 비즈니스 로직, 데이터베이스 트랜잭션 예외 처리 및 비동기 작업 정합성 검증에 집중하라.\n");
                        break;
                    case "FRONTEND":
                        perspectivePrompt.append("- FRONTEND: UI/UX 렌더링, 컴포넌트 상태 변화, 이벤트 핸들링 및 다국어/해상도 호환성 검증에 집중하라.\n");
                        break;
                    case "API":
                        perspectivePrompt.append("- API: 엔드포인트 인터페이스 규격, HTTP 에러 코드 정의 및 데이터 요청/응답 형식의 일치 여부 검증에 집중하라.\n");
                        break;
                    default:
                        perspectivePrompt.append("- ").append(p).append(": 해당 관점의 비즈니스 로직 적합성을 검증하라.\n");
                        break;
                }
            }
            perspectivePrompt.append("\n");
        }

        String mergedCustomPrompt = request.getCustomPrompt() != null ? request.getCustomPrompt() : "";
        if (perspectivePrompt.length() > 0) {
            mergedCustomPrompt = perspectivePrompt.toString() + mergedCustomPrompt;
        }

        // 1. Create and Save AnalysisJob entity
        String perspectiveStr = request.getQaPerspectives() != null ? String.join(",", request.getQaPerspectives()) : "";
        AnalysisJob job = AnalysisJob.builder()
                .analysisId(analysisId)
                .project(project)
                .qaPerspective(perspectiveStr)
                .customPrompt(mergedCustomPrompt)
                .status("WAITING")
                .build();

        AnalysisJob savedJob = analysisJobRepository.save(job);

        // 2. Query target files' S3 paths
        List<UploadedFile> targetFiles;
        if (request.getTargetDocumentIds() == null || request.getTargetDocumentIds().isEmpty()) {
            targetFiles = fileRepository.findByProject_ProjectId(projectId);
        } else {
            targetFiles = fileRepository.findAllById(request.getTargetDocumentIds());
        }

        List<String> s3Paths = targetFiles.stream()
                .map(UploadedFile::getS3Path)
                .collect(Collectors.toList());

        // 3. Trigger analysis processing (either internally or externally)
        if (llmDirect) {
            List<String> targetFileIds = targetFiles.stream()
                    .map(UploadedFile::getFileId)
                    .collect(Collectors.toList());
            analysisProcessor.processDirectly(
                    savedJob.getAnalysisId(),
                    targetFileIds,
                    mergedCustomPrompt,
                    perspectiveStr
            );
        } else {
            triggerExternalAiServer(savedJob, s3Paths, request.getQaPerspectives(), mergedCustomPrompt, request.getLlmApiKey());
        }

        return AnalysisJobResponse.builder()
                .analysisId(savedJob.getAnalysisId())
                .projectId(projectId)
                .status(savedJob.getStatus())
                .createdAt(LocalDateTime.now())
                .qaPerspective(savedJob.getQaPerspective())
                .build();
    }
    private void triggerExternalAiServer(AnalysisJob job, List<String> s3Paths, List<String> perspectives, String customPrompt, String llmApiKey) {
        // Construct FastAPI trigger JSON payload manually to keep dependency-free
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{")
                .append("\"analysisId\":\"").append(escapeJsonString(job.getAnalysisId())).append("\",")
                .append("\"projectId\":\"").append(escapeJsonString(job.getProject().getProjectId())).append("\",")
                .append("\"customPrompt\":\"").append(escapeJsonString(customPrompt)).append("\",")
                .append("\"llmApiKey\":\"").append(escapeJsonString(llmApiKey)).append("\",")
                .append("\"qaPerspectives\":[");
        
        if (perspectives != null) {
            for (int i = 0; i < perspectives.size(); i++) {
                jsonBuilder.append("\"").append(escapeJsonString(perspectives.get(i))).append("\"");
                if (i < perspectives.size() - 1) jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("],");
        jsonBuilder.append("\"s3Paths\":[");
        for (int i = 0; i < s3Paths.size(); i++) {
            jsonBuilder.append("\"").append(escapeJsonString(s3Paths.get(i))).append("\"");
            if (i < s3Paths.size() - 1) jsonBuilder.append(",");
        }
        jsonBuilder.append("]}");

        String payload = jsonBuilder.toString();
        String targetUrl = aiServerUrl + "/api/analysis/trigger";

        // Execute async request
        httpClient.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ).thenAccept(response -> {
            if (response.statusCode() == 202 || response.statusCode() == 200) {
                job.setStatus("PROCESSING");
                analysisJobRepository.save(job);
                System.out.println("Successfully triggered AI analysis for ID: " + job.getAnalysisId());
            } else {
                job.setStatus("FAILED");
                analysisJobRepository.save(job);
                System.err.println("AI server rejected job trigger. Status: " + response.statusCode() + ", Body: " + response.body());
            }
        }).exceptionally(ex -> {
            // Fallback for local testing when FastAPI server is offline:
            // We set status to PROCESSING to simulate async webhook callback during demo/offline.
            System.err.println("Failed to contact AI server (" + targetUrl + "): " + ex.getMessage() + ". Setting status to PROCESSING for local demo mock.");
            job.setStatus("PROCESSING");
            analysisJobRepository.save(job);
            return null;
        });
    }

    private String escapeJsonString(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < ' ') {
                        String t = "000" + Integer.toHexString(ch);
                        sb.append("\\u").append(t.substring(t.length() - 4));
                    } else {
                        sb.append(ch);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Gets status and progress percentage of a running analysis job.
     */
    public AnalysisStatusResponse getAnalysisStatus(String analysisId) {
        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));

        String status = job.getStatus();
        int progress = 0;
        String message = "대기 중...";

        if ("WAITING".equals(status)) {
            progress = 10;
            message = "작업 접수 완료. AI 서버 대기 중...";
        } else if ("PROCESSING".equals(status) || "QUEUED".equals(status)) {
            progress = 60;
            message = "LLM 기반 테스트 케이스 시나리오 분석 진행 중...";
        } else if ("COMPLETED".equals(status)) {
            progress = 100;
            message = "분석 완료.";
        } else if ("FAILED".equals(status)) {
            progress = 0;
            message = job.getSummary() != null ? job.getSummary() : "분석 실패. 로그를 확인하세요.";
        }

        return AnalysisStatusResponse.builder()
                .analysisId(analysisId)
                .status(status)
                .message(message)
                .progressPercentage(progress)
                .build();
    }

    public AnalysisJob getAnalysisEntity(String analysisId) {
        return analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));
    }

    /**
     * Combines all test cases, requirements, risks, and evidence for a completed analysis.
     */
    public AnalysisResultResponse getAnalysisResults(String analysisId) {
        AnalysisJob job = getAnalysisEntity(analysisId);

        List<TestCase> testCases = testCaseRepository.findByAnalysisJob_AnalysisId(analysisId);
        List<String> tcIds = testCases.stream().map(TestCase::getTestCaseId).collect(Collectors.toList());

        // Batch fetch
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

        List<AnalysisResultResponse.TestCaseDto> tcDtos = testCases.stream().map(tc -> {
            List<String> riskTags = risksMap.getOrDefault(tc.getTestCaseId(), Collections.emptyList())
                    .stream().map(RiskItem::getRiskType).collect(Collectors.toList());

            List<AnalysisResultResponse.EvidenceDto> evDtos = evidencesMap.getOrDefault(tc.getTestCaseId(), Collections.emptyList())
                    .stream().map(ev -> AnalysisResultResponse.EvidenceDto.builder()
                            .evidenceId(ev.getEvidenceId())
                            .evidenceText(ev.getEvidenceText())
                            .sourceName(ev.getSourceName())
                            .sourceSection(ev.getSourceSection())
                            .confidenceLevel(tc.getConfidenceLevel() != null ? tc.getConfidenceLevel() : "HIGH")
                            .score(ev.getScore())
                            .build())
                    .collect(Collectors.toList());

            // Convert steps from raw string/CLOB back to List
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
                    .caution(tc.getCaution())
                    .build();
        }).collect(Collectors.toList());

        List<String> missing = new ArrayList<>();
        if (job.getMissingItemsText() != null && !job.getMissingItemsText().isBlank()) {
            String[] split = job.getMissingItemsText().split(";");
            for (String item : split) {
                if (!item.trim().isEmpty()) {
                    missing.add(item.trim());
                }
            }
        }

        return AnalysisResultResponse.builder()
                .analysisId(analysisId)
                .summary(job.getSummary() != null ? job.getSummary() : "전체 기능 명세 요구사항 분석 요약 완료.")
                .testCases(tcDtos)
                .missingItems(missing)
                .build();
    }

    /**
     * Gets all analysis jobs belonging to a project.
     */
    @Transactional(readOnly = true)
    public List<AnalysisJobResponse> getAnalysisJobsByProject(String projectId) {
        List<AnalysisJob> jobs = analysisJobRepository.findByProject_ProjectId(projectId);
        return jobs.stream().map(job -> AnalysisJobResponse.builder()
                .analysisId(job.getAnalysisId())
                .projectId(projectId)
                .status(job.getStatus())
                .createdAt(LocalDateTime.now()) // Dummy created timestamp mapping
                .qaPerspective(job.getQaPerspective())
                .build()).collect(Collectors.toList());
    }

    /**
     * Deletes a single analysis job, cascading deletion of associated entities and S3 report files.
     */
    @Transactional
    public void deleteAnalysisJob(String analysisId) {
        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis job not found: " + analysisId));

        List<String> analysisIds = Collections.singletonList(analysisId);

        // 1. Query and delete reports from DB and S3 physical bucket
        List<Report> reports = reportRepository.findByAnalysisJob_AnalysisIdIn(analysisIds);
        for (Report report : reports) {
            try {
                DeleteObjectRequest deleteOb = DeleteObjectRequest.builder()
                        .bucket(reportsBucket)
                        .key(report.getS3Path())
                        .build();
                s3Client.deleteObject(deleteOb);
                System.out.println("Deleted S3 report object: " + report.getS3Path());
            } catch (Exception e) {
                System.err.println("Failed to delete S3 report: " + report.getS3Path() + ". Error: " + e.getMessage());
            }
        }
        reportRepository.deleteByAnalysisIds(analysisIds);

        // 2. Delete associated evidences, risks, test cases, and requirements from DB
        evidenceRepository.deleteByAnalysisIds(analysisIds);
        riskItemRepository.deleteByAnalysisIds(analysisIds);
        testCaseRepository.deleteByAnalysisIds(analysisIds);
        requirementRepository.deleteByAnalysisIds(analysisIds);

        // 3. Delete analysis job itself
        analysisJobRepository.delete(job);
    }

    /**
     * Recommends QA perspectives based on uploaded document file names and metadata.
     */
    @Transactional(readOnly = true)
    public QaRecommendationResponse getQaRecommendations(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        List<UploadedFile> files = fileRepository.findByProject_ProjectId(projectId);
        
        Set<String> recommended = new LinkedHashSet<>();
        StringBuilder reasonBuilder = new StringBuilder("업로드된 명세서 분석 결과: ");

        boolean hasSecurity = false;
        boolean hasBackend = false;
        boolean hasFrontend = false;
        boolean hasPerformance = false;
        boolean hasApi = false;

        for (UploadedFile file : files) {
            String name = file.getFileName().toLowerCase();
            // Simple keyword analysis on file names
            if (name.contains("login") || name.contains("auth") || name.contains("security") || name.contains("비밀번호") || name.contains("보안") || name.contains("인증")) {
                hasSecurity = true;
            }
            if (name.contains("payment") || name.contains("db") || name.contains("transaction") || name.contains("결제") || name.contains("서버") || name.contains("데이터베이스")) {
                hasBackend = true;
            }
            if (name.contains("screen") || name.contains("ui") || name.contains("ux") || name.contains("화면") || name.contains("컴포넌트") || name.contains("렌더링") || name.contains("front")) {
                hasFrontend = true;
            }
            if (name.contains("latency") || name.contains("load") || name.contains("performance") || name.contains("성능") || name.contains("부하") || name.contains("동시성") || name.contains("대용량")) {
                hasPerformance = true;
            }
            if (name.contains("api") || name.contains("endpoint") || name.contains("interface") || name.contains("인터페이스") || name.contains("통신")) {
                hasApi = true;
            }
        }

        // Default recommendations if no files uploaded or keywords found
        if (files.isEmpty()) {
            recommended.add("API");
            recommended.add("BACKEND");
            reasonBuilder.append("등록된 프로젝트 문서가 없어 기본 QA 관점(API, BACKEND)을 추천합니다.");
        } else {
            List<String> matched = new ArrayList<>();
            if (hasSecurity) {
                recommended.add("SECURITY");
                matched.add("보안/인증");
            }
            if (hasBackend) {
                recommended.add("BACKEND");
                matched.add("백엔드 비즈니스");
            }
            if (hasFrontend) {
                recommended.add("FRONTEND");
                matched.add("프론트엔드 UI");
            }
            if (hasPerformance) {
                recommended.add("PERFORMANCE");
                matched.add("성능/부하");
            }
            if (hasApi) {
                recommended.add("API");
                matched.add("API 인터페이스");
            }

            if (recommended.isEmpty()) {
                // Fallback matched
                recommended.add("API");
                recommended.add("BACKEND");
                reasonBuilder.append("업로드된 문서 명세서 검토 후, 범용 검증을 위한 API 및 BACKEND 관점을 기본 추천합니다.");
            } else {
                reasonBuilder.append("기획서 내 ")
                        .append(String.join(", ", matched))
                        .append(" 관련 키워드가 감지되어 맞춤 관점(")
                        .append(String.join(", ", recommended))
                        .append(")을 추천합니다.");
            }
        }

        return QaRecommendationResponse.builder()
                .recommendedPerspectives(new ArrayList<>(recommended))
                .reason(reasonBuilder.toString())
                .build();
    }
}
