package org.suym.ai.kbs.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Chat Model 配置类
 * 用于配置大语言模型 (LLM)，这里使用 OpenAI 兼容接口 (如 DeepSeek, Moonshot)
 */
@Configuration
public class ChatConfig {

    @Value("${llm.openai.base-url}")
    private String baseUrl;

    @Value("${llm.openai.api-key}")
    private String apiKey;

    @Value("${llm.openai.model-name}")
    private String modelName;

    /**
     * 配置 ChatLanguageModel Bean
     * 文档参考: https://docs.langchain4j.dev/integrations/language-models/openai
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60)) // 设置超时时间
                .logRequests(true) // 打印请求日志，方便调试
                .logResponses(true) // 打印响应日志
                .build();
    }
}
