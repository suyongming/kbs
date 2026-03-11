package org.suym.ai.kbs.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
import org.suym.ai.kbs.dto.GoodsSaveDTO;
import org.suym.ai.kbs.dto.GoodsUpdateDTO;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;

import java.io.IOException;
import java.util.*;
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
     * 4. 查询所有商品 (分页)
     */
    @Operation(summary = "查询商品列表", description = "分页查询所有商品")
    @GetMapping("/list")
    public Map<String, Object> listGoods(
            @Parameter(description = "页码 (从1开始)", example = "1")
            @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        // 创建一个全0的虚拟向量，用于匹配所有数据
        // 注意：必须与 goodsEmbeddingStore 的维度 (512) 一致
        float[] dummyVector = new float[512];
        Embedding dummyEmbedding = Embedding.from(dummyVector);

        // Milvus 的 search 不支持标准的 skip/limit 分页，通常只能通过 topK 实现
        // 这里模拟分页：获取前 (page * size) 条，然后手动截取
        int topK = page * size;

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(dummyEmbedding)
                .maxResults(topK)
                .minScore(0.0)
                .build();

        EmbeddingSearchResult<TextSegment> result = goodsEmbeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> allMatches = result.matches();

        // 手动分页截取
        int start = (page - 1) * size;
        List<Map<String, Object>> records = new ArrayList<>();

        if (start < allMatches.size()) {
            int end = Math.min(start + size, allMatches.size());
            List<EmbeddingMatch<TextSegment>> pageMatches = allMatches.subList(start, end);

            records = pageMatches.stream()
                    .map(match -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("text", match.embedded().text());
                        item.put("metadata", match.embedded().metadata().asMap());
                        return item;
                    })
                    .collect(Collectors.toList());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("total_scanned", allMatches.size()); // 注意：这不是真实的总数，受限于 topK
        response.put("page", page);
        response.put("size", size);
        response.put("records", records);

        return response;
    }

    /**
     * 5. 编辑商品
     * 更新商品信息 (Upsert)
     */
    @Operation(summary = "编辑商品", description = "更新商品信息 (先删除旧的，再插入新的)")
    @PutMapping("/update")
    public Map<String, Object> updateGoods(
            @RequestBody GoodsUpdateDTO dto
    ) {
        if (dto.getSku() == null || dto.getSku().isEmpty()) {
            throw new IllegalArgumentException("SKU is required");
        }

        log.info("Updating goods: {}", dto.getSku());
        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 删除旧数据
            Filter filter = metadataKey("sku").isEqualTo(dto.getSku());
            goodsEmbeddingStore.removeAll(filter);

            // 2. 准备新数据
            // 如果提供了图片 URL，则重新计算向量；否则需要处理 (目前简化为必须提供图片或使用默认向量)
            // 更好的做法是先查出旧数据，拿到旧向量，但这需要 EmbeddingStore 支持 getById
            // 这里简化逻辑：要求更新时必须提供完整信息，包括图片 URL

            if (dto.getImagePath() == null || dto.getImagePath().isEmpty()) {
                throw new IllegalArgumentException("Image path is required for update (to re-calculate vector)");
            }

            Image image = Image.builder().url(dto.getImagePath()).build();
            Embedding imageEmbedding = clipEmbeddingModel.embed(image).content();

            Metadata metadata = new Metadata();
            metadata.add("sku", dto.getSku());
            if (dto.getName() != null) metadata.add("name", dto.getName());
            if (dto.getPrice() != null) metadata.add("price", String.valueOf(dto.getPrice()));
            if (dto.getStatus() != null) metadata.add("status", String.valueOf(dto.getStatus()));
            if (dto.getImagePath() != null) metadata.add("image_path", dto.getImagePath());
            if (dto.getDescription() != null) metadata.add("description", dto.getDescription());

            String textContent = dto.getDescription() != null ? dto.getDescription() : (dto.getName() + " " + dto.getSku());
            TextSegment segment = TextSegment.from(textContent, metadata);

            // 3. 插入新数据
            goodsEmbeddingStore.add(imageEmbedding, segment);

            result.put("success", true);
            result.put("message", "Goods updated successfully: " + dto.getSku());

        } catch (Exception e) {
            log.error("Failed to update goods: {}", dto.getSku(), e);
            result.put("success", false);
            result.put("message", "Failed to update: " + e.getMessage());
        }
        return result;
    }




    /**
     * 1. 商品数据训练接口 (重写版)
     * 接收 JSON 数组，包含商品信息
     */
    @Operation(summary = "训练商品数据", description = "批量上传商品信息 (JSON 数组)，系统将图片向量化并存入知识库")
    @PostMapping(value = "/train")
    public Map<String, Object> trainGoodsData(
            @RequestBody List<GoodsSaveDTO> goodsList
    ) {
        if (clipEmbeddingModel == null) {
            throw new IllegalStateException("CLIP model is not initialized. Cannot process images.");
        }
        if (goodsList == null || goodsList.isEmpty()) {
            throw new IllegalArgumentException("Goods list is empty");
        }

        List<String> successSkus = new ArrayList<>();
        List<String> failedLines = new ArrayList<>();
        int totalProcessed = 0;

        for (GoodsSaveDTO goods : goodsList) {
            totalProcessed++;
            String sku = goods.getSku();
            if (sku == null) sku = "UNKNOWN";

            try {
                String imagePath = goods.getImagePath();
                
                // 处理可能存在的格式问题（如用户输入包含反引号或空格）
                if (imagePath != null) {
                    imagePath = imagePath.trim().replace("`", "");
                }

                if (imagePath == null || imagePath.isEmpty()) {
                    log.warn("Item {}: Missing image_path, skipping.", totalProcessed);
                    failedLines.add("Item " + totalProcessed + " (SKU: " + sku + "): Missing image_path");
                    continue;
                }

                // 1. 加载图片并计算向量
                log.info("Processing SKU: {}, Image: {}", sku, imagePath);
                Image image = Image.builder().url(imagePath).build();

                // 这里的 embed 方法内部现在非常健壮，如果失败会抛出 RuntimeException
                Embedding imageEmbedding = clipEmbeddingModel.embed(image).content();

                // 2. 构建元数据
                Metadata metadata = new Metadata();
                metadata.add("sku", sku);
                if (goods.getId() != null) metadata.add("id", goods.getId()); // 注意：这里的 id 是业务 ID，不是向量库的主键 ID
                if (goods.getName() != null) metadata.add("name", goods.getName());
                if (goods.getPrice() != null) metadata.add("price", String.valueOf(goods.getPrice()));
                if (goods.getStatus() != null) metadata.add("status", String.valueOf(goods.getStatus()));
                metadata.add("image_path", imagePath);
                if (goods.getDescription() != null) metadata.add("description", goods.getDescription());

                // 3. 构建文本段 (用于混合检索或调试)
                String textContent = goods.getDescription() != null ? goods.getDescription() : (goods.getName() + " " + sku);
                TextSegment segment = TextSegment.from(textContent, metadata);

                // 4. 存入 Milvus
                goodsEmbeddingStore.add(imageEmbedding, segment);

                successSkus.add(sku);
                log.info("Successfully ingested SKU: {}", sku);

            } catch (Exception e) {
                log.error("Failed to process item {} (SKU: {}): {}", totalProcessed, sku, e.getMessage());
                failedLines.add("Item " + totalProcessed + " (SKU: " + sku + "): " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total_items", totalProcessed);
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
     * 支持根据 SKU 或 ID 删除
     */
    @Operation(summary = "删除商品", description = "删除商品向量。请提供 sku 或 id 其中之一。")
    @DeleteMapping("/delete")
    public Map<String, Object> deleteGoods(
            @Parameter(description = "商品 SKU (Metadata)") 
            @RequestParam(required = false) String sku,
            @Parameter(description = "向量 ID (Primary Key)") 
            @RequestParam(required = false) String id
    ) {
        if ((sku == null || sku.trim().isEmpty()) && (id == null || id.trim().isEmpty())) {
            throw new IllegalArgumentException("Either 'sku' or 'id' must be provided");
        }

        Map<String, Object> result = new HashMap<>();
        try {
            if (id != null && !id.trim().isEmpty()) {
                log.info("Deleting goods by ID: {}", id);
                goodsEmbeddingStore.removeAll(Collections.singletonList(id));
                result.put("message", "Deleted by ID: " + id);
            } else {
                log.info("Deleting goods by SKU: {}", sku);
                Filter filter = metadataKey("sku").isEqualTo(sku);
                goodsEmbeddingStore.removeAll(filter);
                result.put("message", "Deleted by SKU: " + sku);
            }
            
            result.put("success", true);
        } catch (Exception e) {
            log.error("Failed to delete goods. SKU: {}, ID: {}", sku, id, e);
            result.put("success", false);
            result.put("message", "Failed to delete: " + e.getMessage());
        }
        return result;
    }
}
