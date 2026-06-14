package com.yeonam.tester.config;

import com.yeonam.tester.llm.BedrockLlmClient;
import com.yeonam.tester.llm.LlmClient;
import com.yeonam.tester.llm.OpenAiCompatibleLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "bedrock")
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient bedrockLlmClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.bedrock.model-id:anthropic.claude-3-haiku-20240307-v1:0}") String modelId
    ) {
        return new BedrockLlmClient(region, modelId);
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient openAiLlmClient(
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model:gpt-4o}") String model
    ) {
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey, model);
    }
}
