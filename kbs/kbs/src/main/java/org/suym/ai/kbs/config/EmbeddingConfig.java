package org.suym.ai.kbs.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;

/**
 * AI 模型配置类
 * 配置自定义的 CLIP 模型
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Value("${clip.model.text-path:}")
    private String clipTextModelPath;

    @Value("${clip.model.tokenizer-path:}")
    private String clipTokenizerPath;

    @Value("${clip.model.vision-path:}")
    private String clipVisionModelPath;

    /**
     * 自定义 CLIP Embedding 模型
     * 兼顾文本 (512维) 和 图像 (512维)
     * 标记为 @Primary，使其成为 Spring AI 默认使用的 EmbeddingModel
     */
    @Bean
    @Primary
    public ClipEmbeddingModel clipEmbeddingModel() {
        if (clipTextModelPath == null || clipTextModelPath.isEmpty()) {
            log.warn("CLIP models not configured. Image search will be disabled.");
            return null;
        }
        return new ClipEmbeddingModel(clipTextModelPath, clipTokenizerPath, clipVisionModelPath);
    }
}
