package org.suym.ai.kbs.model.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.suym.ai.kbs.util.ImageUtils;

import java.awt.image.BufferedImage;
import java.nio.LongBuffer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自定义的 CLIP Embedding 模型包装类
 * 支持本地 ONNX 模型推理，包括文本和图像的 Embedding
 */
public class ClipEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ClipEmbeddingModel.class);

    private final OrtEnvironment env;
    private final OrtSession textSession;
    private final OrtSession visionSession;
    private final HuggingFaceTokenizer tokenizer;

    public ClipEmbeddingModel(String textModelPath, String textTokenizerPath, String visionModelPath) {
        log.info("Initializing CLIP models...");
        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

            // 1. 初始化文本模型 (使用原生 ONNX Runtime + DJL Tokenizer)
            // CLIP Text Model usually expects input_ids only or input_ids + attention_mask
            // We handle this manually to avoid LangChain4j's hardcoded 2-input requirement if the model only wants 1.
            this.textSession = env.createSession(textModelPath, opts);

            // Configure tokenizer with padding and truncation
            // CLIP usually uses max length 77
            Map<String, String> tokenizerOptions = Map.of(
                    "padding", "true",
                    "truncation", "true",
                    "maxLength", "77"
            );
            this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(textTokenizerPath), tokenizerOptions);

            // 2. 初始化视觉模型 (使用原生 ONNX Runtime)
            this.visionSession = env.createSession(visionModelPath, opts);

            log.info("CLIP models initialized successfully.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CLIP models", e);
        }
    }

    /**
     * 嵌入文本
     */
    @Override
    public Response<Embedding> embed(String text) {
        try {
            // 1. Tokenize
            Encoding encoding = tokenizer.encode(text);
            long[] inputIds = encoding.getIds();

            // 2. Prepare Tensor
            // Shape: [1, sequence_length]
            long[][] inputIdsArray = new long[1][inputIds.length];
            inputIdsArray[0] = inputIds;

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputIdsArray);

            // 3. Inference
            // Check input names to decide what to pass
            // Typically CLIP text model input is "input_ids" or similar
            String inputName = textSession.getInputNames().iterator().next();

            // If the model expects multiple inputs (e.g. attention_mask), we might need to handle it.
            // But the error "expected [1,1) found 2" suggests it expects ONLY 1 input.
            // So passing just input_ids should work.

            OrtSession.Result result = textSession.run(Collections.singletonMap(inputName, inputTensor));

            // 4. Get Output
            // CLIP Text Model output: [batch_size, embed_dim]
            float[][] outputArray = (float[][]) result.get(0).getValue();
            float[] embeddingVector = outputArray[0];

            // Debug: Print first 5 dimensions of the text vector
            if (embeddingVector.length >= 5) {
                log.info("Text Embedding (first 5 dims): [{}, {}, {}, {}, {}]",
                        embeddingVector[0], embeddingVector[1], embeddingVector[2], embeddingVector[3], embeddingVector[4]);
            } else {
                log.warn("Text Embedding vector too short: length={}", embeddingVector.length);
            }

            result.close();
            inputTensor.close();

            return Response.from(Embedding.from(embeddingVector));

        } catch (Exception e) {
            log.error("CLIP Text Embedding failed: {}", e.getMessage());
            throw new RuntimeException("Failed to embed text with CLIP model", e);
        }
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return embed(textSegment.text());
    }

    /**
     * 嵌入图片 (CLIP 核心功能)
     */
    public Response<Embedding> embed(Image image) {
        log.info("Embedding image: {}", image.url());
        try {
            // 1. 加载图片
            BufferedImage bufferedImage = ImageUtils.loadImage(image.url().toString());

            // 2. 预处理 (Resize + Normalize + CHW Layout)
            float[][][][] inputData = ImageUtils.preprocessImage(bufferedImage);

            // 3. 创建 Tensor
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputData);

            // 4. 推理
            // 尝试只取第一个输入节点名称
            String inputName = visionSession.getInputNames().iterator().next();
            OrtSession.Result result = visionSession.run(Collections.singletonMap(inputName, inputTensor));

            // 5. 获取输出
            float[][] outputArray = (float[][]) result.get(0).getValue();
            float[] embeddingVector = outputArray[0];

            // Debug: Print first 5 dimensions of the image vector
            if (embeddingVector.length >= 5) {
                log.info("Image Embedding (first 5 dims) for {}: [{}, {}, {}, {}, {}]",
                        image.url(),
                        embeddingVector[0], embeddingVector[1], embeddingVector[2], embeddingVector[3], embeddingVector[4]);
            } else {
                log.warn("Image Embedding vector too short: length={}", embeddingVector.length);
            }

            result.close();
            inputTensor.close();

            return Response.from(Embedding.from(embeddingVector));

        } catch (Exception e) {
            log.error("Failed to embed image: {}", image.url(), e);
            throw new RuntimeException("Failed to embed image", e);
        }
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            embeddings.add(embed(segment).content());
        }
        return Response.from(embeddings);
    }
}
