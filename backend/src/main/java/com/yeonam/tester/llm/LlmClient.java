package com.yeonam.tester.llm;

import java.util.List;

public interface LlmClient {
    String generateTestCases(String requirementText, String customPrompt, String qaPerspective);

    /**
     * 기존 테스트 케이스와 중복 없이 보완 시나리오를 count개 생성한다.
     * 반환값은 JSON 배열 문자열: [{"testCaseName":"...","testScenario":"...","expectedResult":"...","priority":"..."}]
     */
    String generateSupplementaryScenarios(String summary, String qaPerspective, List<String> existingNames, int count);
}
