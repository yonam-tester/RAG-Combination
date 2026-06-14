package com.yeonam.tester.dto;

import java.util.List;

public class SupplementaryScenarioDto {
    private String testCaseName;
    private String testScenario;
    private String precondition;
    private String testSteps;
    private String expectedResult;
    private String priority;
    private String technique;
    private List<String> riskTags;

    public SupplementaryScenarioDto() {}

    public SupplementaryScenarioDto(String testCaseName, String testScenario, String precondition,
                                    String testSteps, String expectedResult, String priority,
                                    String technique, List<String> riskTags) {
        this.testCaseName = testCaseName;
        this.testScenario = testScenario;
        this.precondition = precondition;
        this.testSteps = testSteps;
        this.expectedResult = expectedResult;
        this.priority = priority;
        this.technique = technique;
        this.riskTags = riskTags;
    }

    public String getTestCaseName() { return testCaseName; }
    public void setTestCaseName(String testCaseName) { this.testCaseName = testCaseName; }

    public String getTestScenario() { return testScenario; }
    public void setTestScenario(String testScenario) { this.testScenario = testScenario; }

    public String getPrecondition() { return precondition; }
    public void setPrecondition(String precondition) { this.precondition = precondition; }

    public String getTestSteps() { return testSteps; }
    public void setTestSteps(String testSteps) { this.testSteps = testSteps; }

    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getTechnique() { return technique; }
    public void setTechnique(String technique) { this.technique = technique; }

    public List<String> getRiskTags() { return riskTags; }
    public void setRiskTags(List<String> riskTags) { this.riskTags = riskTags; }

    public static SupplementaryScenarioDtoBuilder builder() {
        return new SupplementaryScenarioDtoBuilder();
    }

    public static class SupplementaryScenarioDtoBuilder {
        private String testCaseName;
        private String testScenario;
        private String precondition;
        private String testSteps;
        private String expectedResult;
        private String priority;
        private String technique;
        private List<String> riskTags;

        public SupplementaryScenarioDtoBuilder testCaseName(String v) { this.testCaseName = v; return this; }
        public SupplementaryScenarioDtoBuilder testScenario(String v) { this.testScenario = v; return this; }
        public SupplementaryScenarioDtoBuilder precondition(String v) { this.precondition = v; return this; }
        public SupplementaryScenarioDtoBuilder testSteps(String v) { this.testSteps = v; return this; }
        public SupplementaryScenarioDtoBuilder expectedResult(String v) { this.expectedResult = v; return this; }
        public SupplementaryScenarioDtoBuilder priority(String v) { this.priority = v; return this; }
        public SupplementaryScenarioDtoBuilder technique(String v) { this.technique = v; return this; }
        public SupplementaryScenarioDtoBuilder riskTags(List<String> v) { this.riskTags = v; return this; }

        public SupplementaryScenarioDto build() {
            return new SupplementaryScenarioDto(testCaseName, testScenario, precondition,
                    testSteps, expectedResult, priority, technique, riskTags);
        }
    }
}
