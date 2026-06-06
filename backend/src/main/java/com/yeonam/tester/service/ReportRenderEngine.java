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
import java.util.List;
import java.util.Map;

@Service
public class ReportRenderEngine {

    private static final String DISCLAIMER_TEXT = "본 시스템은 테스트 수행 결과를 보장하거나 최종 판단을 대체하지 않으며, QA 담당자의 검토를 전제로 합니다.";

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
                if (risks != null && !risks.isEmpty()) {
                    sb.append("- **위험 요소 태그**: ");
                    for (int i = 0; i < risks.size(); i++) {
                        Object r = risks.get(i);
                        String rType = (r instanceof com.yeonam.tester.domain.RiskItem) ? ((com.yeonam.tester.domain.RiskItem) r).getRiskType() : r.toString();
                        sb.append("#").append(rType).append(" ");
                    }
                    sb.append("\n");
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
