package com.yeonam.tester.dto;

import java.util.List;

public class AnalysisResultResponse {
    private String analysisId;
    private String summary;
    private List<TestCaseDto> testCases;
    private List<String> missingItems;

    public AnalysisResultResponse() {}

    public AnalysisResultResponse(String analysisId, String summary, List<TestCaseDto> testCases, List<String> missingItems) {
        this.analysisId = analysisId;
        this.summary = summary;
        this.testCases = testCases;
        this.missingItems = missingItems;
    }

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<TestCaseDto> getTestCases() { return testCases; }
    public void setTestCases(List<TestCaseDto> testCases) { this.testCases = testCases; }

    public List<String> getMissingItems() { return missingItems; }
    public void setMissingItems(List<String> missingItems) { this.missingItems = missingItems; }

    public static AnalysisResultResponseBuilder builder() {
        return new AnalysisResultResponseBuilder();
    }

    public static class TestCaseDto {
        private String testCaseId;
        private String testCaseName;
        private String testScenario;
        private String precondition;
        private List<String> testSteps;
        private String expectedResult;
        private String priority;
        private List<String> riskTags;
        private List<String> relatedRequirements;
        private List<EvidenceDto> evidences;
        private String category;
        private String technique;
        private String tddHint;
        private String negativeScenario;
        private String caution;
        private String analysisId;

        public TestCaseDto() {}

        public TestCaseDto(String testCaseId, String testCaseName, String testScenario, String precondition, List<String> testSteps, String expectedResult, String priority, List<String> riskTags, List<String> relatedRequirements, List<EvidenceDto> evidences, String category, String technique, String tddHint, String negativeScenario, String caution, String analysisId) {
            this.testCaseId = testCaseId;
            this.testCaseName = testCaseName;
            this.testScenario = testScenario;
            this.precondition = precondition;
            this.testSteps = testSteps;
            this.expectedResult = expectedResult;
            this.priority = priority;
            this.riskTags = riskTags;
            this.relatedRequirements = relatedRequirements;
            this.evidences = evidences;
            this.category = category;
            this.technique = technique;
            this.tddHint = tddHint;
            this.negativeScenario = negativeScenario;
            this.caution = caution;
            this.analysisId = analysisId;
        }

        public String getTestCaseId() { return testCaseId; }
        public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }

        public String getTestCaseName() { return testCaseName; }
        public void setTestCaseName(String testCaseName) { this.testCaseName = testCaseName; }

        public String getTestScenario() { return testScenario; }
        public void setTestScenario(String testScenario) { this.testScenario = testScenario; }

        public String getPrecondition() { return precondition; }
        public void setPrecondition(String precondition) { this.precondition = precondition; }

        public List<String> getTestSteps() { return testSteps; }
        public void setTestSteps(List<String> testSteps) { this.testSteps = testSteps; }

        public String getExpectedResult() { return expectedResult; }
        public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public List<String> getRiskTags() { return riskTags; }
        public void setRiskTags(List<String> riskTags) { this.riskTags = riskTags; }

        public List<String> getRelatedRequirements() { return relatedRequirements; }
        public void setRelatedRequirements(List<String> relatedRequirements) { this.relatedRequirements = relatedRequirements; }

        public List<EvidenceDto> getEvidences() { return evidences; }
        public void setEvidences(List<EvidenceDto> evidences) { this.evidences = evidences; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getTechnique() { return technique; }
        public void setTechnique(String technique) { this.technique = technique; }

        public String getTddHint() { return tddHint; }
        public void setTddHint(String tddHint) { this.tddHint = tddHint; }

        public String getNegativeScenario() { return negativeScenario; }
        public void setNegativeScenario(String negativeScenario) { this.negativeScenario = negativeScenario; }

        public String getCaution() { return caution; }
        public void setCaution(String caution) { this.caution = caution; }

        public String getAnalysisId() { return analysisId; }
        public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

        public static TestCaseDtoBuilder builder() {
            return new TestCaseDtoBuilder();
        }

        public static class TestCaseDtoBuilder {
            private String testCaseId;
            private String testCaseName;
            private String testScenario;
            private String precondition;
            private List<String> testSteps;
            private String expectedResult;
            private String priority;
            private List<String> riskTags;
            private List<String> relatedRequirements;
            private List<EvidenceDto> evidences;
            private String category;
            private String technique;
            private String tddHint;
            private String negativeScenario;
            private String caution;
            private String analysisId;

            public TestCaseDtoBuilder testCaseId(String testCaseId) {
                this.testCaseId = testCaseId;
                return this;
            }

            public TestCaseDtoBuilder testCaseName(String testCaseName) {
                this.testCaseName = testCaseName;
                return this;
            }

            public TestCaseDtoBuilder testScenario(String testScenario) {
                this.testScenario = testScenario;
                return this;
            }

            public TestCaseDtoBuilder precondition(String precondition) {
                this.precondition = precondition;
                return this;
            }

            public TestCaseDtoBuilder testSteps(List<String> testSteps) {
                this.testSteps = testSteps;
                return this;
            }

            public TestCaseDtoBuilder expectedResult(String expectedResult) {
                this.expectedResult = expectedResult;
                return this;
            }

            public TestCaseDtoBuilder priority(String priority) {
                this.priority = priority;
                return this;
            }

            public TestCaseDtoBuilder riskTags(List<String> riskTags) {
                this.riskTags = riskTags;
                return this;
            }

            public TestCaseDtoBuilder relatedRequirements(List<String> relatedRequirements) {
                this.relatedRequirements = relatedRequirements;
                return this;
            }

            public TestCaseDtoBuilder evidences(List<EvidenceDto> evidences) {
                this.evidences = evidences;
                return this;
            }

            public TestCaseDtoBuilder category(String category) {
                this.category = category;
                return this;
            }

            public TestCaseDtoBuilder technique(String technique) {
                this.technique = technique;
                return this;
            }

            public TestCaseDtoBuilder tddHint(String tddHint) {
                this.tddHint = tddHint;
                return this;
            }

            public TestCaseDtoBuilder negativeScenario(String negativeScenario) {
                this.negativeScenario = negativeScenario;
                return this;
            }

            public TestCaseDtoBuilder caution(String caution) {
                this.caution = caution;
                return this;
            }

            public TestCaseDtoBuilder analysisId(String analysisId) {
                this.analysisId = analysisId;
                return this;
            }

            public TestCaseDto build() {
                return new TestCaseDto(testCaseId, testCaseName, testScenario, precondition, testSteps, expectedResult, priority, riskTags, relatedRequirements, evidences, category, technique, tddHint, negativeScenario, caution, analysisId);
            }
        }
    }

    public static class EvidenceDto {
        private String evidenceId;
        private String evidenceText;
        private String sourceName;
        private String sourceSection;
        private String confidenceLevel;
        private Double score;

        public EvidenceDto() {}

        public EvidenceDto(String evidenceId, String evidenceText, String sourceName, String sourceSection, String confidenceLevel, Double score) {
            this.evidenceId = evidenceId;
            this.evidenceText = evidenceText;
            this.sourceName = sourceName;
            this.sourceSection = sourceSection;
            this.confidenceLevel = confidenceLevel;
            this.score = score;
        }

        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }

        public String getEvidenceText() { return evidenceText; }
        public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }

        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }

        public String getSourceSection() { return sourceSection; }
        public void setSourceSection(String sourceSection) { this.sourceSection = sourceSection; }

        public String getConfidenceLevel() { return confidenceLevel; }
        public void setConfidenceLevel(String confidenceLevel) { this.confidenceLevel = confidenceLevel; }

        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }

        public static EvidenceDtoBuilder builder() {
            return new EvidenceDtoBuilder();
        }

        public static class EvidenceDtoBuilder {
            private String evidenceId;
            private String evidenceText;
            private String sourceName;
            private String sourceSection;
            private String confidenceLevel;
            private Double score;

            public EvidenceDtoBuilder evidenceId(String evidenceId) {
                this.evidenceId = evidenceId;
                return this;
            }

            public EvidenceDtoBuilder evidenceText(String evidenceText) {
                this.evidenceText = evidenceText;
                return this;
            }

            public EvidenceDtoBuilder sourceName(String sourceName) {
                this.sourceName = sourceName;
                return this;
            }

            public EvidenceDtoBuilder sourceSection(String sourceSection) {
                this.sourceSection = sourceSection;
                return this;
            }

            public EvidenceDtoBuilder confidenceLevel(String confidenceLevel) {
                this.confidenceLevel = confidenceLevel;
                return this;
            }

            public EvidenceDtoBuilder score(Double score) {
                this.score = score;
                return this;
            }

            public EvidenceDto build() {
                return new EvidenceDto(evidenceId, evidenceText, sourceName, sourceSection, confidenceLevel, score);
            }
        }
    }

    public static class AnalysisResultResponseBuilder {
        private String analysisId;
        private String summary;
        private List<TestCaseDto> testCases;
        private List<String> missingItems;

        public AnalysisResultResponseBuilder analysisId(String analysisId) {
            this.analysisId = analysisId;
            return this;
        }

        public AnalysisResultResponseBuilder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public AnalysisResultResponseBuilder testCases(List<TestCaseDto> testCases) {
            this.testCases = testCases;
            return this;
        }

        public AnalysisResultResponseBuilder missingItems(List<String> missingItems) {
            this.missingItems = missingItems;
            return this;
        }

        public AnalysisResultResponse build() {
            return new AnalysisResultResponse(analysisId, summary, testCases, missingItems);
        }
    }
}
