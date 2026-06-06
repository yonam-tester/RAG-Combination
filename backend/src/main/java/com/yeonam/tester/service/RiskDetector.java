package com.yeonam.tester.service;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class RiskDetector {

    /**
     * Analyzes texts to identify potential security or functionality risks.
     */
    public List<String> detectRisks(String requirementText, String scenarioText) {
        List<String> risks = new ArrayList<>();
        
        String combined = ((requirementText != null ? requirementText : "") + " " + 
                           (scenarioText != null ? scenarioText : "")).toLowerCase();

        if (combined.contains("로그인") || combined.contains("인증") || combined.contains("패스워드") || combined.contains("비밀번호") || combined.contains("세션")) {
            risks.add("인증_실패");
            risks.add("보안_위험");
        }
        if (combined.contains("네트워크") || combined.contains("서버") || combined.contains("통신") || combined.contains("api") || combined.contains("연동")) {
            risks.add("통신_장애");
        }
        if (combined.contains("입력") || combined.contains("폼") || combined.contains("검증") || combined.contains("유효성") || combined.contains("포맷")) {
            risks.add("입력값_오류");
        }
        if (combined.contains("결제") || combined.contains("카드") || combined.contains("환불") || combined.contains("주문")) {
            risks.add("결제_오류");
        }
        if (combined.contains("권한") || combined.contains("관리자") || combined.contains("허용") || combined.contains("차단")) {
            risks.add("권한_침해");
        }

        // Return a default if no specific risks are detected
        if (risks.isEmpty()) {
            risks.add("일반_기능");
        }

        return risks;
    }
}
