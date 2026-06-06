package com.yeonam.tester.config;

import com.yeonam.tester.llm.BedrockLlmClient;
import com.yeonam.tester.llm.LlmClient;
import com.yeonam.tester.llm.MockLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
    public LlmClient mockLlmClient() {
        return new MockLlmClient();
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "bedrock")
    public LlmClient bedrockLlmClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.bedrock.model-id:anthropic.claude-3-haiku-20240307-v1:0}") String modelId
    ) {
        return new BedrockLlmClient(region, modelId);
    }
}
