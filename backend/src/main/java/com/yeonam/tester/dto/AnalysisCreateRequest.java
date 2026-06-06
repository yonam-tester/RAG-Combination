package com.yeonam.tester.dto;

import java.util.List;

public class AnalysisCreateRequest {
    private List<String> targetDocumentIds;
    private List<String> qaPerspectives;
    private String customPrompt;
    private String llmApiKey;

    public AnalysisCreateRequest() {}

    public AnalysisCreateRequest(List<String> targetDocumentIds, List<String> qaPerspectives, String customPrompt) {
        this.targetDocumentIds = targetDocumentIds;
        this.qaPerspectives = qaPerspectives;
        this.customPrompt = customPrompt;
    }

    public AnalysisCreateRequest(List<String> targetDocumentIds, List<String> qaPerspectives, String customPrompt, String llmApiKey) {
        this.targetDocumentIds = targetDocumentIds;
        this.qaPerspectives = qaPerspectives;
        this.customPrompt = customPrompt;
        this.llmApiKey = llmApiKey;
    }

    public List<String> getTargetDocumentIds() { return targetDocumentIds; }
    public void setTargetDocumentIds(List<String> targetDocumentIds) { this.targetDocumentIds = targetDocumentIds; }

    public List<String> getQaPerspectives() { return qaPerspectives; }
    public void setQaPerspectives(List<String> qaPerspectives) { this.qaPerspectives = qaPerspectives; }

    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }

    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String llmApiKey) { this.llmApiKey = llmApiKey; }

    public static AnalysisCreateRequestBuilder builder() {
        return new AnalysisCreateRequestBuilder();
    }

    public static class AnalysisCreateRequestBuilder {
        private List<String> targetDocumentIds;
        private List<String> qaPerspectives;
        private String customPrompt;
        private String llmApiKey;

        public AnalysisCreateRequestBuilder targetDocumentIds(List<String> targetDocumentIds) {
            this.targetDocumentIds = targetDocumentIds;
            return this;
        }

        public AnalysisCreateRequestBuilder qaPerspectives(List<String> qaPerspectives) {
            this.qaPerspectives = qaPerspectives;
            return this;
        }

        public AnalysisCreateRequestBuilder customPrompt(String customPrompt) {
            this.customPrompt = customPrompt;
            return this;
        }

        public AnalysisCreateRequestBuilder llmApiKey(String llmApiKey) {
            this.llmApiKey = llmApiKey;
            return this;
        }

        public AnalysisCreateRequest build() {
            return new AnalysisCreateRequest(targetDocumentIds, qaPerspectives, customPrompt, llmApiKey);
        }
    }
}
