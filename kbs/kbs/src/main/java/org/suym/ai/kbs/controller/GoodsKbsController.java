package org.suym.ai.kbs.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.suym.ai.kbs.dto.GoodsSaveDTO;
import org.suym.ai.kbs.dto.GoodsUpdateDTO;
import org.suym.ai.kbs.dto.base.JsonResult;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;
import org.suym.ai.kbs.vo.GoodsSearchVO;
import org.suym.ai.kbs.vo.PageVO;
import org.suym.ai.kbs.vo.TrainResultVO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品知识库业务 Controller (Spring AI + PGVector 版)
 * 负责商品数据的训练 (Ingestion) 和 自然语言查询 (Retrieval)
 */
@RestController
@RequestMapping("/api/goods")
@Tag(name = "商品知识库管理", description = "提供商品数据训练、自然语言查商品接口")
public class GoodsKbsController {

    private static final Logger log = LoggerFactory.getLogger(GoodsKbsController.class);

    private final VectorStore vectorStore;
    private final ClipEmbeddingModel clipEmbeddingModel;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public GoodsKbsController(VectorStore vectorStore,
                              ClipEmbeddingModel clipEmbeddingModel,
                              JdbcClient jdbcClient,
                              ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.clipEmbeddingModel = clipEmbeddingModel;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 6. 以图搜商品 (Image to Goods Search)
     * API: POST /api/goods/search-by-image
     */
    @Operation(summary = "以图搜商品", description = "上传图片，在商品库中搜索最相似的商品")
    @PostMapping(value = "/search-by-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult<List<GoodsSearchVO>> searchGoodsByImage(
            @Parameter(description = "用于搜索的图片", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "返回结果数量", required = false)
            @RequestParam(value = "limit", defaultValue = "5") int limit
    ) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Invalid file type. Only images are allowed.");
        }

        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }

        // 1. 保存查询图片到临时文件
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "kbs_goods_search_images");
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        Path tempFilePath = tempDir.resolve(UUID.randomUUID().toString() + "_" + file.getOriginalFilename());

        try {
            file.transferTo(tempFilePath.toFile());
            log.info("Temporary search image saved to: {}", tempFilePath);

            // 2. 直接使用图片路径作为查询内容
            // ClipEmbeddingModel 会自动检测路径并加载图片计算向量
            // 注意：这里我们不能直接用 vectorStore.similaritySearch(String)，因为它默认是 EmbedText
            // 但我们的 ClipEmbeddingModel 已经改造成支持自动识别 Image Path
            // 所以直接传路径即可！
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.query(tempFilePath.toString()).withTopK(limit)
            );

            // 3. 格式化返回结果
            List<GoodsSearchVO> resultList = results.stream()
                    .map(doc -> {
                        Double distance = 0.0;
                        Object distObj = doc.getMetadata().get("distance");
                        if (distObj instanceof Float) {
                            distance = ((Float) distObj).doubleValue();
                        } else if (distObj instanceof Double) {
                            distance = (Double) distObj;
                        }

                        return GoodsSearchVO.builder()
                                .score(1.0 - distance) // PGVector 默认返回距离，需要转换 (1-distance 粗略近似 score)
                                .text(doc.getContent())
                                .metadata(doc.getMetadata())
                                .build();
                    })
                    .collect(Collectors.toList());

            return JsonResult.ok(resultList);

        } finally {
            // 5. 清理临时文件
            try {
                Files.deleteIfExists(tempFilePath);
                log.debug("Temporary search image deleted: {}", tempFilePath);
            } catch (IOException e) {
                log.warn("Failed to delete temporary search image: {}", tempFilePath, e);
            }
        }
    }

    /**
     * 4. 查询所有商品 (分页)
     * 使用 JdbcClient 直接查询 PGVector 表，不再进行向量计算
     */
    @Operation(summary = "查询商品列表", description = "分页查询所有商品")
    @GetMapping("/list")
    public JsonResult<PageVO<GoodsSearchVO>> listGoods(
            @Parameter(description = "页码 (从1开始)", example = "1")
            @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        int offset = (page - 1) * size;
        
        // 查询总数
        Long total = jdbcClient.sql("SELECT count(*) FROM vector_store")
                .query(Long.class)
                .single();

        // 分页查询 (仅查询内容和元数据，不查向量)
        List<GoodsSearchVO> records = jdbcClient.sql("SELECT content, metadata FROM vector_store LIMIT :limit OFFSET :offset")
                .param("limit", size)
                .param("offset", offset)
                .query((rs, rowNum) -> {
                    String content = rs.getString("content");
                    String metadataJson = rs.getString("metadata");
                    
                    Map<String, Object> metadata = new HashMap<>();
                    if (metadataJson != null && !metadataJson.isEmpty()) {
                        try {
                            metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            log.error("Failed to parse metadata JSON", e);
                            // Fallback to empty map or handle error
                        }
                    }
                    
                    return GoodsSearchVO.builder()
                            .text(content)
                            .metadata(metadata)
                            .build();
                })
                .list();

        PageVO<GoodsSearchVO> pageVO = PageVO.<GoodsSearchVO>builder()
                .total(total)
                .page(page)
                .size(size)
                .records(records)
                .build();

        return JsonResult.ok(pageVO);
    }

    /**
     * 5. 编辑商品
     */
    @Operation(summary = "编辑商品", description = "更新商品信息")
    @PutMapping("/update")
    public JsonResult<String> updateGoods(
            @RequestBody GoodsUpdateDTO dto
    ) {
        if (dto.getSku() == null || dto.getSku().isEmpty()) {
            throw new IllegalArgumentException("SKU is required");
        }

        // 1. 删除旧数据 (PGVector 中通常没有直接根据 metadata 删除的 API，需要用 ID)
        // Spring AI 的 delete 只能根据 ID 删除
        // 这是一个痛点。我们可以用 JdbcClient 根据 metadata 删除
        String deleteSql = "DELETE FROM vector_store WHERE metadata->>'sku' = ?";
        int deleted = jdbcClient.sql(deleteSql)
                .param(dto.getSku())
                .update();
        log.info("Deleted {} old records for SKU: {}", deleted, dto.getSku());

        // 2. 插入新数据
        if (dto.getImagePath() == null || dto.getImagePath().isEmpty()) {
            throw new IllegalArgumentException("Image path is required");
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sku", dto.getSku());
        if (dto.getName() != null) metadata.put("name", dto.getName());
        if (dto.getPrice() != null) metadata.put("price", dto.getPrice());
        metadata.put("image_path", dto.getImagePath());
        if (dto.getDescription() != null) metadata.put("description", dto.getDescription());

        Document doc = new Document(dto.getImagePath(), metadata);
        vectorStore.add(List.of(doc));

        return JsonResult.ok("Goods updated successfully");
    }

    /**
     * 1. 商品数据训练接口
     */
    @Operation(summary = "训练商品数据", description = "批量上传商品信息")
    @PostMapping(value = "/train")
    public JsonResult<TrainResultVO> trainGoodsData(
            @RequestBody List<GoodsSaveDTO> goodsList
    ) {
        List<String> successSkus = new ArrayList<>();
        List<String> failedLines = new ArrayList<>();
        int totalProcessed = 0;

        List<Document> documentsBatch = new ArrayList<>();

        for (GoodsSaveDTO goods : goodsList) {
            totalProcessed++;
            String sku = goods.getSku();
            try {
                String imagePath = goods.getImagePath();
                if (imagePath != null) imagePath = imagePath.trim().replace("`", "");

                if (imagePath == null || imagePath.isEmpty()) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sku", sku);
                metadata.put("name", goods.getName());
                metadata.put("price", goods.getPrice());
                metadata.put("image_path", imagePath);
                metadata.put("description", goods.getDescription());

                // 创建 Document，内容是图片路径
                // ClipEmbeddingModel 会识别这个路径并计算 Image Embedding
                Document doc = new Document(imagePath, metadata);
                documentsBatch.add(doc);
                successSkus.add(sku);

            } catch (Exception e) {
                log.error("Error preparing SKU: {}", sku, e);
                failedLines.add("SKU " + sku + ": " + e.getMessage());
            }
        }

        // 批量存入 PGVector
        if (!documentsBatch.isEmpty()) {
            vectorStore.add(documentsBatch);
            log.info("Batch inserted {} documents", documentsBatch.size());
        }

        TrainResultVO resultVO = TrainResultVO.builder()
                .totalItems(totalProcessed)
                .successCount(successSkus.size())
                .successSkus(successSkus)
                .failedCount(failedLines.size())
                .failedLines(failedLines)
                .build();

        return JsonResult.ok(resultVO);
    }

    /**
     * 2. 用户自然语言查询接口
     */
    @Operation(summary = "自然语言搜商品", description = "输入文字描述")
    @GetMapping("/search")
    public JsonResult<List<GoodsSearchVO>> searchGoods(
            @RequestParam("query") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit
    ) {
        // 直接查询
        // ClipEmbeddingModel 会识别这是文本，计算 Text Embedding
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(limit)
        );

        List<GoodsSearchVO> resultList = results.stream()
                .map(doc -> {
                    Double distance = 0.0;
                    Object distObj = doc.getMetadata().get("distance");
                    if (distObj instanceof Float) {
                        distance = ((Float) distObj).doubleValue();
                    } else if (distObj instanceof Double) {
                        distance = (Double) distObj;
                    }
                    
                    return GoodsSearchVO.builder()
                            .score(1.0 - distance)
                            .text(doc.getContent())
                            .metadata(doc.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());

        return JsonResult.ok(resultList);
    }

    /**
     * 3. 删除商品接口
     */
    @Operation(summary = "删除商品", description = "删除商品向量")
    @DeleteMapping("/delete")
    public JsonResult<String> deleteGoods(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String id
    ) {
        if (id != null && !id.isEmpty()) {
            vectorStore.delete(List.of(id));
            return JsonResult.ok("Deleted by ID: " + id);
        } else if (sku != null && !sku.isEmpty()) {
            // PGVector 根据 metadata 删除
            String deleteSql = "DELETE FROM vector_store WHERE metadata->>'sku' = ?";
            int deleted = jdbcClient.sql(deleteSql).param(sku).update();
            return JsonResult.ok("Deleted by SKU: " + sku + ", count: " + deleted);
        }
        return JsonResult.fail("Provide sku or id");
    }
}
