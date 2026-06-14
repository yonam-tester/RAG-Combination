package com.yeonam.tester.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.yeonam.tester.domain.Project;
import com.yeonam.tester.domain.AnalysisJob;
import com.yeonam.tester.domain.Requirement;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ReportRenderEngine {

    private static final String DISCLAIMER_TEXT = "본 시스템은 테스트 수행 결과를 보장하거나 최종 판단을 대체하지 않으며, QA 담당자의 검토를 전제로 합니다.";

    private static final Map<java.util.Set<String>, String> RISK_SENTENCE_MAP;

    static {
        RISK_SENTENCE_MAP = new java.util.LinkedHashMap<>();
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "권한_부여", "권한검증", "관리자", "AUTHORIZATION_BYPASS", "PRIVILEGE_ESCALATION"
        )), "접근 권한이 없는 사용자의 요청이 차단되는지 검증이 필요합니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "입력검증", "유효성검사", "입력오류", "데이터유효성"
        )), "잘못된 입력값에 대해 서버가 적절히 거부하는지 확인이 필요합니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "오류처리", "예외처리", "서버오류", "오류"
        )), "예외 상황 발생 시 적절한 오류 응답이 반환되어야 합니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "로그인", "전화번호", "인증"
        )), "인증 정보의 정확성과 보안 처리 여부를 확인해야 합니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "출석", "위치검증", "GPS", "위치오류"
        )), "위치 데이터의 정확성과 출석 처리 로직의 신뢰성을 검증해야 합니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "엑셀업로드", "파일다운로드"
        )), "파일 입출력 처리 중 데이터 손실이나 오류가 없는지 확인이 필요합니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "TEST_COUPLING", "TEST_STATE_POLLUTION"
        )), "테스트 간 상태 공유로 인한 결과 오염 가능성이 있습니다.");
        RISK_SENTENCE_MAP.put(new java.util.HashSet<>(java.util.Arrays.asList(
            "CREDENTIAL_EXPOSURE"
        )), "민감 정보(비밀번호, 토큰)가 코드나 로그에 노출될 위험이 있습니다.");
    }

    private List<String> buildRiskSentences(List<?> risks) {
        if (risks == null || risks.isEmpty()) return Collections.emptyList();

        java.util.Set<String> tags = new java.util.HashSet<>();
        for (Object r : risks) {
            String tag = (r instanceof com.yeonam.tester.domain.RiskItem) ? ((com.yeonam.tester.domain.RiskItem) r).getRiskType() : r.toString();
            tags.add(tag);
        }

        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        for (Map.Entry<java.util.Set<String>, String> entry : RISK_SENTENCE_MAP.entrySet()) {
            for (String tag : tags) {
                if (entry.getKey().contains(tag)) {
                    added.add(entry.getValue());
                    break;
                }
            }
        }
        return new java.util.ArrayList<>(added);
    }

    /**
     * Renders a structured Markdown report from the assembled model.
     */
    @SuppressWarnings("unchecked")
    public String renderMarkdown(Map<String, Object> model) {
        Project project = (Project) model.get("project");
        AnalysisJob job = (AnalysisJob) model.get("job");
        List<Requirement> requirements = (List<Requirement>) model.get("requirements");
        List<Map<String, Object>> testCases = (List<Map<String, Object>>) model.get("testCases");

        StringBuilder sb = new StringBuilder();
        sb.append("# 📌 연암 테스터 QA 검증 보고서\n\n");

        // Section 0: Glossary
        sb.append("## 0. 이 보고서를 읽는 방법\n\n");
        sb.append("이 보고서는 AI가 프로젝트 요구사항 문서를 분석하여 자동 생성한 QA 검증 보고서입니다.\n");
        sb.append("테스트 및 QA 경험이 없어도 아래 용어 설명을 참고하면 내용을 이해할 수 있습니다.\n\n");
        sb.append("| 용어 | 설명 |\n");
        sb.append("|------|------|\n");
        sb.append("| 테스트 케이스 (TC) | 특정 기능이 올바르게 동작하는지 확인하기 위한 하나의 검증 시나리오 |\n");
        sb.append("| 우선순위 | HIGH: 반드시 검증 / MEDIUM: 가능하면 검증 / LOW: 여유 시 검증 |\n");
        sb.append("| 신뢰도 | AI가 이 테스트 케이스를 얼마나 확신하는지의 정도 (HIGH/MEDIUM/LOW) |\n");
        sb.append("| 사전 조건 | 테스트를 시작하기 전에 반드시 갖춰야 할 환경이나 상태 |\n");
        sb.append("| 테스트 절차 | 테스트를 수행하는 순서대로 나열한 단계별 행동 목록 |\n");
        sb.append("| 기대 결과 | 테스트가 통과할 때 나타나야 하는 정상적인 결과 |\n");
        sb.append("| 위험 요인 | 이 기능에서 발생할 수 있는 잠재적인 문제나 취약점 |\n");
        sb.append("| 근거 출처 | AI가 이 테스트 케이스를 생성할 때 참고한 문서 |\n");
        sb.append("| 보완 시나리오 | RAG 분석 외에 LLM이 추가로 제안한 테스트 시나리오 |\n\n");

        // 1. Project Overview
        sb.append("## 1. 분석 대상 개요\n");
        sb.append("- **프로젝트명**: ").append(project.getName()).append("\n");
        if (project.getDescription() != null) {
            sb.append("- **프로젝트 개요**: ").append(project.getDescription()).append("\n");
        }
        if (project.getGithubUrl() != null) {
            sb.append("- **GitHub 리포지토리**: ").append(project.getGithubUrl()).append("\n");
            sb.append("- **기본 브랜치**: ").append(project.getGithubBranch() != null ? project.getGithubBranch() : "main").append("\n");
        }
        sb.append("- **분석 생성일**: ").append(LocalDateTimeFormatter(job)).append("\n\n");

        // 2. Requirements & Perspectives
        sb.append("## 2. 요구사항 및 분석 관점\n");
        sb.append("- **QA 관점**: ").append(job.getQaPerspective() != null ? job.getQaPerspective() : "기본 관점").append("\n");
        if (job.getCustomPrompt() != null && !job.getCustomPrompt().isBlank()) {
            sb.append("- **사용자 맞춤 프롬프트**: ").append(job.getCustomPrompt()).append("\n");
        }
        sb.append("\n### 2.1 요구사항 명세 요약\n");
        sb.append(job.getSummary() != null ? job.getSummary() : "추출된 요구사항 요약 정보가 없습니다.").append("\n\n");

        // 3. Test Cases
        sb.append("## 3. 테스트 케이스 생성 결과\n\n");
        if (testCases.isEmpty()) {
            sb.append("생성된 테스트 케이스 시나리오가 없습니다.\n\n");
        } else {
            for (Map<String, Object> tc : testCases) {
                sb.append("### ").append(tc.get("testCaseId")).append(": ").append(tc.get("testCaseName")).append("\n");
                sb.append("- **우선순위**: `").append(tc.get("priority")).append("` | **신뢰도**: `").append(tc.get("confidenceLevel") != null ? tc.get("confidenceLevel") : "MEDIUM").append("`\n");
                sb.append("- **검증 요구사항**: ").append(tc.get("requirementId")).append(" - ").append(tc.get("requirementText")).append("\n");
                sb.append("- **테스트 시나리오**: ").append(tc.get("testScenario")).append("\n");
                if (tc.get("precondition") != null && !((String) tc.get("precondition")).isBlank()) {
                    sb.append("- **사전 조건**: ").append(tc.get("precondition")).append("\n");
                }

                // Steps
                sb.append("- **테스트 절차**:\n");
                Object stepsObj = tc.get("testSteps");
                if (stepsObj instanceof List) {
                    List<String> steps = (List<String>) stepsObj;
                    for (String step : steps) {
                        sb.append("  - ").append(step).append("\n");
                    }
                } else if (stepsObj instanceof String) {
                    String stepsStr = (String) stepsObj;
                    // Check if it's formatted as lines
                    String[] lines = stepsStr.split("\n");
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            sb.append("  - ").append(line.replace("- ", "").replace("* ", "").trim()).append("\n");
                        }
                    }
                } else {
                    sb.append("  - 절차가 등록되지 않았습니다.\n");
                }

                sb.append("- **기대 결과**: ").append(tc.get("expectedResult")).append("\n");

                // Risks
                List<?> risks = (List<?>) tc.get("risks");
                List<String> riskSentences = buildRiskSentences(risks);
                if (!riskSentences.isEmpty()) {
                    sb.append("- **위험 요인**:\n");
                    for (String sentence : riskSentences) {
                        sb.append("  - ").append(sentence).append("\n");
                    }
                }

                // Evidences (RAG)
                List<?> evidences = (List<?>) tc.get("evidences");
                if (evidences != null && !evidences.isEmpty()) {
                    sb.append("- **매핑된 RAG 근거 문서 조각**:\n");
                    for (Object evObj : evidences) {
                        if (evObj instanceof com.yeonam.tester.domain.Evidence) {
                            com.yeonam.tester.domain.Evidence ev = (com.yeonam.tester.domain.Evidence) evObj;
                            sb.append("  > [**").append(ev.getSourceName()).append("** (").append(ev.getSourceSection() != null ? ev.getSourceSection() : "전체").append(")] ")
                                    .append(ev.getEvidenceText()).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }

        // 4. 보완 시나리오 섹션 (additionalScenarios가 있을 경우만)
        List<Map<String, Object>> additionalScenarios =
                (List<Map<String, Object>>) model.getOrDefault("additionalScenarios", Collections.emptyList());
        if (!additionalScenarios.isEmpty()) {
            sb.append("## 4. 보완 테스트 시나리오 (LLM 추가 생성)\n\n");
            int baseIndex = testCases.size() + 1;
            for (int i = 0; i < additionalScenarios.size(); i++) {
                Map<String, Object> s = additionalScenarios.get(i);
                sb.append("### 보완-").append(baseIndex + i).append(": ")
                  .append(s.getOrDefault("testCaseName", "보완 시나리오")).append("\n");
                sb.append("- **우선순위**: `")
                  .append(s.getOrDefault("priority", "MEDIUM")).append("`\n");
                sb.append("- **테스트 시나리오**: ")
                  .append(s.getOrDefault("testScenario", "")).append("\n");
                sb.append("- **기대 결과**: ")
                  .append(s.getOrDefault("expectedResult", "")).append("\n");
                sb.append("\n");
            }
        }

        // 4. Appendix: Omissions and Disclaimer
        sb.append("## Appendix. 보완 필요 사항 및 한계 고지\n\n");
        sb.append("### A.1 보완 필요 사항\n");
        // We can check if there are custom missing items. If none, append a helpful default.
        sb.append("- 로그인 및 인증 실패 시의 세션 타임아웃 예외 흐름 검증 절차 보완을 권장합니다.\n");
        sb.append("- 업로드된 명세서의 파일 전처리 한계로 인해, 비기능적 성능/부하 요구사항에 대한 테스트 케이스는 수동 작성이 요구됩니다.\n\n");

        sb.append("### A.2 시스템 한계 고지 (Disclaimer)\n");
        sb.append("> ").append(DISCLAIMER_TEXT).append("\n");

        return sb.toString();
    }

    /**
     * Converts a raw markdown string into PDF bytes using OpenPDF.
     */
    public byte[] renderPdf(String markdownText) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document();
        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            // Set simple styles
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA, 20, Font.BOLD);
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL);
            Font italicFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.ITALIC);

            String[] lines = markdownText.split("\n");
            for (String line : lines) {
                if (line.startsWith("# ")) {
                    Paragraph p = new Paragraph(line.substring(2).trim(), titleFont);
                    p.setSpacingAfter(15);
                    document.add(p);
                } else if (line.startsWith("## ")) {
                    Paragraph p = new Paragraph(line.substring(3).trim(), sectionFont);
                    p.setSpacingBefore(10);
                    p.setSpacingAfter(8);
                    document.add(p);
                } else if (line.startsWith("### ")) {
                    Paragraph p = new Paragraph(line.substring(4).trim(), sectionFont);
                    p.setSpacingBefore(8);
                    p.setSpacingAfter(5);
                    document.add(p);
                } else if (line.startsWith("> ")) {
                    Paragraph p = new Paragraph(line.substring(2).trim(), italicFont);
                    p.setIndentationLeft(20);
                    p.setSpacingAfter(5);
                    document.add(p);
                } else if (!line.trim().isEmpty()) {
                    Paragraph p = new Paragraph(line.trim(), bodyFont);
                    p.setSpacingAfter(3);
                    document.add(p);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to render PDF report", e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return baos.toByteArray();
    }

    private String LocalDateTimeFormatter(AnalysisJob job) {
        // Just return current time as formatted or standard string
        return java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
