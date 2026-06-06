package com.yeonam.tester.dto;

import java.util.List;

public class TestCaseUpdateRequest {
    private String testCaseName;
    private String testScenario;
    private String precondition;
    private List<String> testSteps;
    private String expectedResult;
    private String priority;
    private String category;
    private String technique;
    private String tddHint;
    private String negativeScenario;

    public TestCaseUpdateRequest() {}

    public TestCaseUpdateRequest(String testCaseName, String testScenario, String precondition, List<String> testSteps, String expectedResult, String priority) {
        this.testCaseName = testCaseName;
        this.testScenario = testScenario;
        this.precondition = precondition;
        this.testSteps = testSteps;
        this.expectedResult = expectedResult;
        this.priority = priority;
    }

    public TestCaseUpdateRequest(String testCaseName, String testScenario, String precondition, List<String> testSteps, String expectedResult, String priority, String category, String technique, String tddHint, String negativeScenario) {
        this.testCaseName = testCaseName;
        this.testScenario = testScenario;
        this.precondition = precondition;
        this.testSteps = testSteps;
        this.expectedResult = expectedResult;
        this.priority = priority;
        this.category = category;
        this.technique = technique;
        this.tddHint = tddHint;
        this.negativeScenario = negativeScenario;
    }

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

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTechnique() { return technique; }
    public void setTechnique(String technique) { this.technique = technique; }

    public String getTddHint() { return tddHint; }
    public void setTddHint(String tddHint) { this.tddHint = tddHint; }

    public String getNegativeScenario() { return negativeScenario; }
    public void setNegativeScenario(String negativeScenario) { this.negativeScenario = negativeScenario; }
}
