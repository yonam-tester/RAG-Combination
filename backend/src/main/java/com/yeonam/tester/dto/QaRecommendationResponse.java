package com.yeonam.tester.dto;

import java.util.List;

public class QaRecommendationResponse {
    private List<String> recommendedPerspectives;
    private String reason;

    public QaRecommendationResponse() {}

    public QaRecommendationResponse(List<String> recommendedPerspectives, String reason) {
        this.recommendedPerspectives = recommendedPerspectives;
        this.reason = reason;
    }

    public List<String> getRecommendedPerspectives() { return recommendedPerspectives; }
    public void setRecommendedPerspectives(List<String> recommendedPerspectives) { this.recommendedPerspectives = recommendedPerspectives; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public static QaRecommendationResponseBuilder builder() {
        return new QaRecommendationResponseBuilder();
    }

    public static class QaRecommendationResponseBuilder {
        private List<String> recommendedPerspectives;
        private String reason;

        public QaRecommendationResponseBuilder recommendedPerspectives(List<String> recommendedPerspectives) {
            this.recommendedPerspectives = recommendedPerspectives;
            return this;
        }

        public QaRecommendationResponseBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public QaRecommendationResponse build() {
            return new QaRecommendationResponse(recommendedPerspectives, reason);
        }
    }
}
