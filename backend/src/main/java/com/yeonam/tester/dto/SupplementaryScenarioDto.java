package com.yeonam.tester.dto;

public class SupplementaryScenarioDto {
    private String testCaseName;
    private String testScenario;
    private String expectedResult;
    private String priority;

    public SupplementaryScenarioDto() {}

    public SupplementaryScenarioDto(String testCaseName, String testScenario, String expectedResult, String priority) {
        this.testCaseName = testCaseName;
        this.testScenario = testScenario;
        this.expectedResult = expectedResult;
        this.priority = priority;
    }

    public String getTestCaseName() { return testCaseName; }
    public void setTestCaseName(String testCaseName) { this.testCaseName = testCaseName; }

    public String getTestScenario() { return testScenario; }
    public void setTestScenario(String testScenario) { this.testScenario = testScenario; }

    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public static SupplementaryScenarioDtoBuilder builder() {
        return new SupplementaryScenarioDtoBuilder();
    }

    public static class SupplementaryScenarioDtoBuilder {
        private String testCaseName;
        private String testScenario;
        private String expectedResult;
        private String priority;

        public SupplementaryScenarioDtoBuilder testCaseName(String testCaseName) {
            this.testCaseName = testCaseName;
            return this;
        }

        public SupplementaryScenarioDtoBuilder testScenario(String testScenario) {
            this.testScenario = testScenario;
            return this;
        }

        public SupplementaryScenarioDtoBuilder expectedResult(String expectedResult) {
            this.expectedResult = expectedResult;
            return this;
        }

        public SupplementaryScenarioDtoBuilder priority(String priority) {
            this.priority = priority;
            return this;
        }

        public SupplementaryScenarioDto build() {
            return new SupplementaryScenarioDto(testCaseName, testScenario, expectedResult, priority);
        }
    }
}
