package com.yeonam.tester.service;

import org.springframework.stereotype.Component;

@Component
public class PriorityEvaluator {

    /**
     * Evaluates a test case's priority based on requirement and scenario texts.
     */
    public String evaluatePriority(String requirementText, String scenarioText) {
        if (requirementText == null) requirementText = "";
        if (scenarioText == null) scenarioText = "";

        String reqLower = requirementText.toLowerCase();
        String scLower = scenarioText.toLowerCase();

        int score = 0;

        // Keywords indicating critical system features
        if (reqLower.contains("결제") || reqLower.contains("주문") || reqLower.contains("돈")) score += 3;
        if (reqLower.contains("인증") || reqLower.contains("로그인") || reqLower.contains("보안") || reqLower.contains("권한")) score += 3;
        if (reqLower.contains("필수") || reqLower.contains("반드시") || reqLower.contains("critical")) score += 2;

        // Keywords indicating exception/error conditions
        if (scLower.contains("오류") || scLower.contains("실패") || scLower.contains("에러") || scLower.contains("차단") || scLower.contains("제한")) {
            score += 2;
        }

        if (scLower.contains("로그아웃") || scLower.contains("세션")) {
            score += 1;
        }

        if (score >= 6) {
            return "HIGH";
        } else if (score >= 3) {
            return "MEDIUM";
        } else if (score >= 1) {
            return "LOW";
        } else {
            return "추가 검토 필요";
        }
    }
}
