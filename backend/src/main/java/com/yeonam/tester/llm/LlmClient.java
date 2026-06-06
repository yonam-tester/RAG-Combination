package com.yeonam.tester.llm;

public interface LlmClient {
    String generateTestCases(String requirementText, String customPrompt, String qaPerspective);
}
