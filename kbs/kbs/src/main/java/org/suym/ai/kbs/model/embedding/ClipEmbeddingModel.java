package org.suym.ai.kbs.model.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.suym.ai.kbs.util.ImageUtils;

import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 自定义的 CLIP Embedding 模型包装类 (适配 Spring AI)
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

            this.textSession = env.createSession(textModelPath, opts);

            Map<String, String> tokenizerOptions = Map.of(
                    "padding", "true",
                    "truncation", "true",
                    "maxLength", "77"
            );
            this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(textTokenizerPath), tokenizerOptions);

            this.visionSession = env.createSession(visionModelPath, opts);

            log.info("CLIP models initialized successfully.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CLIP models", e);
        }
    }

    @Override
    public float[] embed(Document document) {
        String content = document.getContent();
        if (isImageUrl(content)) {
            return embedImage(content);
        } else {
            return embedText(content);
        }
    }

    @Override
    public float[] embed(String text) {
        if (isImageUrl(text)) {
            return embedImage(text);
        } else {
            return embedText(text);
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;
        
        for (String text : request.getInstructions()) {
            float[] vector;
            if (isImageUrl(text)) {
                vector = embedImage(text);
            } else {
                vector = embedText(text);
            }
            embeddings.add(new Embedding(vector, index++));
        }
        
        return new EmbeddingResponse(embeddings);
    }

    private boolean isImageUrl(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.startsWith("http") || lower.startsWith("file:") || 
               lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    /**
     * 文本嵌入核心逻辑
     */
    public float[] embedText(String text) {
        try {
            Encoding encoding = tokenizer.encode(text);
            long[] inputIds = encoding.getIds();

            long[][] inputIdsArray = new long[1][inputIds.length];
            inputIdsArray[0] = inputIds;

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputIdsArray);

            String inputName = textSession.getInputNames().iterator().next();
            OrtSession.Result result = textSession.run(Collections.singletonMap(inputName, inputTensor));

            float[][] outputArray = (float[][]) result.get(0).getValue();
            float[] embeddingVector = outputArray[0];

            result.close();
            inputTensor.close();

            return embeddingVector;

        } catch (Exception e) {
            log.error("CLIP Text Embedding failed: {}", e.getMessage());
            throw new RuntimeException("Failed to embed text with CLIP model", e);
        }
    }

    /**
     * 图片嵌入核心逻辑
     */
    public float[] embedImage(String imageUrl) {
        log.info("Embedding image: {}", imageUrl);
        try {
            BufferedImage bufferedImage = ImageUtils.loadImage(imageUrl);
            float[][][][] inputData = ImageUtils.preprocessImage(bufferedImage);

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputData);

            String inputName = visionSession.getInputNames().iterator().next();
            OrtSession.Result result = visionSession.run(Collections.singletonMap(inputName, inputTensor));

            float[][] outputArray = (float[][]) result.get(0).getValue();
            float[] embeddingVector = outputArray[0];

            result.close();
            inputTensor.close();

            return embeddingVector;

        } catch (Exception e) {
            log.error("Failed to embed image: {}", imageUrl, e);
            throw new RuntimeException("Failed to embed image", e);
        }
    }
}
