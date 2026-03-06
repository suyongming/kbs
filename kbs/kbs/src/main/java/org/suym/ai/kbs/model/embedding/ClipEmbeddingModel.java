package org.suym.ai.kbs.model.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.suym.ai.kbs.config.EmbeddingConfig;

import java.util.List;

/**
 * 自定义的 CLIP Embedding 模型包装类
 * 由于 LangChain4j 目前官方仓库中没有预打包的 CLIP 构件，
 * 我们通过加载本地的 ONNX 模型来实现。
 */
public class ClipEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ClipEmbeddingModel.class);

    private final OnnxEmbeddingModel textModel;
    private final OnnxEmbeddingModel visionModel;

    public ClipEmbeddingModel(String textModelPath, String textTokenizerPath, 
                             String visionModelPath, String visionTokenizerPath) {
        log.info("Initializing CLIP models...");
        this.textModel = new OnnxEmbeddingModel(textModelPath, textTokenizerPath, PoolingMode.MEAN);
        // 注意：这里的 visionModel 逻辑取决于具体的 CLIP ONNX 导出结构
        // 实际上 vision 部分通常不需要 tokenizer，但 OnnxEmbeddingModel 构造函数目前强制要求
        this.visionModel = new OnnxEmbeddingModel(visionModelPath, visionTokenizerPath, PoolingMode.MEAN);
    }

    /**
     * 嵌入文本
     */
    @Override
    public Response<Embedding> embed(String text) {
        return textModel.embed(text);
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return textModel.embed(textSegment);
    }

    /**
     * 嵌入图片 (CLIP 核心功能)
     * 注意：这里需要根据具体的 ONNX 模型输入输出进行调整
     */
    public Response<Embedding> embed(Image image) {
        log.info("Embedding image: {}", image.url());
        // 在实际生产中，您需要将 Image (URL/Base64) 转换为模型需要的 Tensor
        // 这里目前复用 OnnxEmbeddingModel 的基础结构，
        // 建议在本地使用时，确保 visionModel 的输入与 textModel 能够对齐
        // 如果 vision 部分的 ONNX 处理比较复杂，可能需要引入额外的处理逻辑
        return visionModel.embed(image.url().toString()); 
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        return textModel.embedAll(textSegments);
    }
}
