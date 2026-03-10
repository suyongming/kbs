package org.suym.ai.kbs.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.langchain4j.store.embedding.filter.Filter;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 商品知识库业务 Controller
 * 负责商品数据的训练 (Ingestion) 和 自然语言查询 (Retrieval)
 */
@RestController
@RequestMapping("/api/goods")
@Tag(name = "商品知识库管理", description = "提供商品数据训练、自然语言查商品接口")
public class GoodsKbsController {

    private static final Logger log = LoggerFactory.getLogger(GoodsKbsController.class);

    private final EmbeddingStore<TextSegment> goodsEmbeddingStore;
    private final ClipEmbeddingModel clipEmbeddingModel;
    private final ObjectMapper objectMapper;

    public GoodsKbsController(@Qualifier("goodsEmbeddingStore") EmbeddingStore<TextSegment> goodsEmbeddingStore,
                              @Autowired(required = false) ClipEmbeddingModel clipEmbeddingModel) {
        this.goodsEmbeddingStore = goodsEmbeddingStore;
        this.clipEmbeddingModel = clipEmbeddingModel;
        this.objectMapper = new ObjectMapper();
    }


    /**
     * 1. 商品数据训练接口 (重写版)
     * 接收 txt 文件，每一行是一个 JSON 对象，包含商品信息
     */
    @Operation(summary = "训练商品数据", description = "上传包含商品信息的 txt 文件 (JSON Lines 格式)，系统将图片向量化并存入知识库")
    @PostMapping(value = "/train", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> trainGoodsData(
            @Parameter(description = "包含商品信息的 txt 文件", required = true)
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (clipEmbeddingModel == null) {
            throw new IllegalStateException("CLIP model is not initialized. Cannot process images.");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        List<String> successSkus = new ArrayList<>();
        List<String> failedLines = new ArrayList<>();
        int totalProcessed = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.trim().isEmpty()) continue;
                totalProcessed++;

                String sku = "UNKNOWN"; // For logging
                try {
                    Map<String, Object> goodsMap = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    
                    // 提取关键字段
                    sku = String.valueOf(goodsMap.get("sku"));
                    String imagePath = (String) goodsMap.get("image_path");
                    String description = (String) goodsMap.get("description");
                    
                    if (imagePath == null || imagePath.isEmpty()) {
                        log.warn("Line {}: Missing image_path, skipping.", lineNum);
                        failedLines.add("Line " + lineNum + " (SKU: " + sku + "): Missing image_path");
                        continue;
                    }

                    // 1. 加载图片并计算向量
                    log.info("Processing SKU: {}, Image: {}", sku, imagePath);
                    Image image = Image.builder().url(imagePath).build();
                    
                    // 这里的 embed 方法内部现在非常健壮，如果失败会抛出 RuntimeException
                    Embedding imageEmbedding = clipEmbeddingModel.embed(image).content();

                    // 2. 构建元数据
                    Metadata metadata = new Metadata();
                    for (Map.Entry<String, Object> entry : goodsMap.entrySet()) {
                        if (entry.getValue() != null) {
                            metadata.add(entry.getKey(), String.valueOf(entry.getValue()));
                        }
                    }

                    // 3. 构建文本段 (用于混合检索或调试)
                    String textContent = description != null ? description : (goodsMap.get("name") + " " + sku);
                    TextSegment segment = TextSegment.from(textContent, metadata);
                    
                    // 4. 存入 Milvus
                    goodsEmbeddingStore.add(imageEmbedding, segment);
                    
                    successSkus.add(sku);
                    log.info("Successfully ingested SKU: {}", sku);

                } catch (Exception e) {
                    log.error("Failed to process line {} (SKU: {}): {}", lineNum, sku, e.getMessage());
                    // e.printStackTrace(); // Optional: print stack trace for debugging
                    failedLines.add("Line " + lineNum + " (SKU: " + sku + "): " + e.getMessage());
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total_lines", totalProcessed);
        result.put("success_count", successSkus.size());
        result.put("success_skus", successSkus);
        result.put("failed_count", failedLines.size());
        result.put("failed_lines", failedLines);
        
        return result;
    }

    /**
     * 2. 用户自然语言查询接口
     * 输入文字描述，通过 CLIP 文本编码器生成向量，在商品库中检索
     */
    @Operation(summary = "自然语言搜商品", description = "输入文字描述（如'红色连衣裙'），搜索最匹配的商品")
    @GetMapping("/search")
    public List<Map<String, Object>> searchGoods(
            @Parameter(description = "搜索关键词", required = true)
            @RequestParam("query") String query,
            @Parameter(description = "返回结果数量", required = false)
            @RequestParam(value = "limit", defaultValue = "5") int limit
    ) {
        if (clipEmbeddingModel == null) {
            throw new IllegalStateException("CLIP model is not initialized.");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        log.info("Searching goods by query: {}", query);

        // 1. 计算文本的 CLIP 向量
        Embedding queryEmbedding = clipEmbeddingModel.embed(query).content();

        // 2. 在商品库中检索
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(limit)
                .minScore(0.0) // 阈值可调
                .build();

        EmbeddingSearchResult<TextSegment> result = goodsEmbeddingStore.search(searchRequest);

        // 3. 格式化返回结果
        return result.matches().stream()
                .map(match -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("score", match.score());
                    item.put("text", match.embedded().text());
                    item.put("metadata", match.embedded().metadata().asMap());
                    return item;
                })
                .collect(Collectors.toList());
    }

    /**
     * 3. 删除商品接口
     * 根据 SKU 删除商品向量
     */
    @Operation(summary = "删除商品", description = "根据 SKU 删除商品向量")
    @DeleteMapping("/delete")
    public Map<String, Object> deleteGoods(
            @Parameter(description = "商品 SKU", required = true)
            @RequestParam("sku") String sku
    ) {
        if (sku == null || sku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU cannot be empty");
        }

        log.info("Deleting goods by SKU: {}", sku);

        Map<String, Object> result = new HashMap<>();
        try {
            // 使用 metadata 过滤删除
            Filter filter = metadataKey("sku").isEqualTo(sku);
            goodsEmbeddingStore.removeAll(filter);

            result.put("success", true);
            result.put("message", "Deleted goods with SKU: " + sku);
        } catch (Exception e) {
            log.error("Failed to delete goods with SKU: {}", sku, e);
            result.put("success", false);
            result.put("message", "Failed to delete: " + e.getMessage());
        }
        return result;
    }

}
