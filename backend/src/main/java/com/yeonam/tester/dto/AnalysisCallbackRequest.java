package com.yeonam.tester.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAlias;

public class AnalysisCallbackRequest {
    private String summary;
    private List<TestCaseDto> testCases;
    private List<String> missingItems;
    private String status;
    private String errorMessage;

    public AnalysisCallbackRequest() {}

    public AnalysisCallbackRequest(String summary, List<TestCaseDto> testCases, List<String> missingItems, String status, String errorMessage) {
        this.summary = summary;
        this.testCases = testCases;
        this.missingItems = missingItems;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<TestCaseDto> getTestCases() { return testCases; }
    public void setTestCases(List<TestCaseDto> testCases) { this.testCases = testCases; }

    public List<String> getMissingItems() { return missingItems; }
    public void setMissingItems(List<String> missingItems) { this.missingItems = missingItems; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public static AnalysisCallbackRequestBuilder builder() {
        return new AnalysisCallbackRequestBuilder();
    }

    public static class TestCaseDto {
        private String testCaseId;
        private String requirementId;
        private String requirementText;
        private String testCaseName;
        private String testScenario;
        private String precondition;
        private List<String> testSteps;
        private String expectedResult;
        private String priority;
        private String confidenceLevel;
        private List<String> riskTags;
        @JsonAlias({"evidences", "evidence_list"})
        private List<EvidenceDto> evidences;
        private String category;
        private String technique;
        private String tddHint;
        private String negativeScenario;
        private String caution;

        public TestCaseDto() {}

        public TestCaseDto(String testCaseId, String requirementId, String requirementText, String testCaseName, String testScenario, String precondition, List<String> testSteps, String expectedResult, String priority, String confidenceLevel, List<String> riskTags, List<EvidenceDto> evidences, String category, String technique, String tddHint, String negativeScenario, String caution) {
            this.testCaseId = testCaseId;
            this.requirementId = requirementId;
            this.requirementText = requirementText;
            this.testCaseName = testCaseName;
            this.testScenario = testScenario;
            this.precondition = precondition;
            this.testSteps = testSteps;
            this.expectedResult = expectedResult;
            this.priority = priority;
            this.confidenceLevel = confidenceLevel;
            this.riskTags = riskTags;
            this.evidences = evidences;
            this.category = category;
            this.technique = technique;
            this.tddHint = tddHint;
            this.negativeScenario = negativeScenario;
            this.caution = caution;
        }

        public String getTestCaseId() { return testCaseId; }
        public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }

        public String getRequirementId() { return requirementId; }
        public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

        public String getRequirementText() { return requirementText; }
        public void setRequirementText(String requirementText) { this.requirementText = requirementText; }

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

        public String getConfidenceLevel() { return confidenceLevel; }
        public void setConfidenceLevel(String confidenceLevel) { this.confidenceLevel = confidenceLevel; }

        public List<String> getRiskTags() { return riskTags; }
        public void setRiskTags(List<String> riskTags) { this.riskTags = riskTags; }

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

        public static TestCaseDtoBuilder builder() {
            return new TestCaseDtoBuilder();
        }

        public static class TestCaseDtoBuilder {
            private String testCaseId;
            private String requirementId;
            private String requirementText;
            private String testCaseName;
            private String testScenario;
            private String precondition;
            private List<String> testSteps;
            private String expectedResult;
            private String priority;
            private String confidenceLevel;
            private List<String> riskTags;
            private List<EvidenceDto> evidences;
            private String category;
            private String technique;
            private String tddHint;
            private String negativeScenario;
            private String caution;

            public TestCaseDtoBuilder testCaseId(String testCaseId) {
                this.testCaseId = testCaseId;
                return this;
            }

            public TestCaseDtoBuilder requirementId(String requirementId) {
                this.requirementId = requirementId;
                return this;
            }

            public TestCaseDtoBuilder requirementText(String requirementText) {
                this.requirementText = requirementText;
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

            public TestCaseDtoBuilder confidenceLevel(String confidenceLevel) {
                this.confidenceLevel = confidenceLevel;
                return this;
            }

            public TestCaseDtoBuilder riskTags(List<String> riskTags) {
                this.riskTags = riskTags;
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

            public TestCaseDto build() {
                return new TestCaseDto(testCaseId, requirementId, requirementText, testCaseName, testScenario, precondition, testSteps, expectedResult, priority, confidenceLevel, riskTags, evidences, category, technique, tddHint, negativeScenario, caution);
            }
        }
    }

    public static class EvidenceDto {
        @JsonAlias({"chunkId", "chunk_id"})
        private String chunkId;
        @JsonAlias({"evidenceText", "evidence_text", "text"})
        private String evidenceText;
        @JsonAlias({"sourceName", "source_name"})
        private String sourceName;
        @JsonAlias({"sourceSection", "source_section"})
        private String sourceSection;
        private Double score;

        public EvidenceDto() {}

        public EvidenceDto(String chunkId, String evidenceText, String sourceName, String sourceSection) {
            this.chunkId = chunkId;
            this.evidenceText = evidenceText;
            this.sourceName = sourceName;
            this.sourceSection = sourceSection;
        }

        public EvidenceDto(String chunkId, String evidenceText, String sourceName, String sourceSection, Double score) {
            this.chunkId = chunkId;
            this.evidenceText = evidenceText;
            this.sourceName = sourceName;
            this.sourceSection = sourceSection;
            this.score = score;
        }

        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }

        public String getEvidenceText() { return evidenceText; }
        public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }

        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }

        public String getSourceSection() { return sourceSection; }
        public void setSourceSection(String sourceSection) { this.sourceSection = sourceSection; }

        public static EvidenceDtoBuilder builder() {
            return new EvidenceDtoBuilder();
        }

        public static class EvidenceDtoBuilder {
            private String chunkId;
            private String evidenceText;
            private String sourceName;
            private String sourceSection;

            public EvidenceDtoBuilder chunkId(String chunkId) {
                this.chunkId = chunkId;
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

            public EvidenceDto build() {
                return new EvidenceDto(chunkId, evidenceText, sourceName, sourceSection);
            }
        }
    }

    public static class AnalysisCallbackRequestBuilder {
        private String summary;
        private List<TestCaseDto> testCases;
        private List<String> missingItems;
        private String status;
        private String errorMessage;

        public AnalysisCallbackRequestBuilder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public AnalysisCallbackRequestBuilder testCases(List<TestCaseDto> testCases) {
            this.testCases = testCases;
            return this;
        }

        public AnalysisCallbackRequestBuilder missingItems(List<String> missingItems) {
            this.missingItems = missingItems;
            return this;
        }

        public AnalysisCallbackRequestBuilder status(String status) {
            this.status = status;
            return this;
        }

        public AnalysisCallbackRequestBuilder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public AnalysisCallbackRequest build() {
            return new AnalysisCallbackRequest(summary, testCases, missingItems, status, errorMessage);
        }
    }
}
