package org.suym.ai.kbs.controller;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 图片搜索业务 Controller (Demo)
 * 提供以图搜图、以文搜图等功能
 */
@RestController
@RequestMapping("/api/images")
@Tag(name = "图片搜索管理", description = "提供图片上传、以图搜图、以文搜图等接口")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    // 专用图片库 (image_gallery_v1)
    private final EmbeddingStore<TextSegment> imageEmbeddingStore;
    private final ClipEmbeddingModel clipEmbeddingModel;

    public ImageController(@Qualifier("galleryEmbeddingStore") EmbeddingStore<TextSegment> imageEmbeddingStore,
                           @Autowired(required = false) ClipEmbeddingModel clipEmbeddingModel) {
        this.imageEmbeddingStore = imageEmbeddingStore;
        this.clipEmbeddingModel = clipEmbeddingModel;
    }

    /**
     * 3. 图片上传与索引 (Image Ingestion) - Demo
     * API: POST /api/images/upload
     * 逻辑: 上传图片 -> 保存到本地/云存储 -> 计算 CLIP 向量 -> 存入 Milvus
     */
    @Operation(summary = "上传图片 (以图搜图)", description = "上传图片，计算 CLIP 向量并存入图库")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadImage(
            @Parameter(description = "需要上传的图片文件", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "图片描述（可选，用于元数据）")
            @RequestParam(value = "description", required = false, defaultValue = "") String description
    ) throws IOException {
        if (file.isEmpty()) {
            return "File is empty";
        }

        // 1. 保存图片到本地临时目录 (CLIP 模型需要读取实际文件)
        String originalFilename = file.getOriginalFilename();
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "kbs_images");
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        String fileExt = originalFilename != null && originalFilename.contains(".") 
                ? originalFilename.substring(originalFilename.lastIndexOf(".")) 
                : ".jpg";
        String uniqueFilename = UUID.randomUUID().toString() + fileExt;
        Path tempFilePath = tempDir.resolve(uniqueFilename);
        file.transferTo(tempFilePath.toFile());

        log.info("Image saved to: {}", tempFilePath);

        // 2. 加载图片并计算 CLIP 向量
        Image image = Image.builder().url(tempFilePath.toUri().toString()).build();
        log.info("Calculating CLIP embedding...");
        
        Embedding imageEmbedding = clipEmbeddingModel.embed(image).content();
        
        // 3. 存入 Milvus
        TextSegment segment = TextSegment.from(tempFilePath.toString(), Metadata.from("description", description));
        imageEmbeddingStore.add(imageEmbedding, segment);

        return "Image uploaded successfully. ID: " + uniqueFilename;
    }

    /**
     * 4. 以图搜图 (Image Search) - Demo
     * API: POST /api/images/search
     * 逻辑: 上传查询图片 -> 计算向量 -> 在 Milvus 中搜索最相似的图片
     */
    @Operation(summary = "以图搜图", description = "上传一张图片，在图库中搜索最相似的图片")
    @PostMapping(value = "/search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Map<String, Object>> searchImage(
            @Parameter(description = "用于搜索的图片", required = true)
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (clipEmbeddingModel == null) {
            throw new IllegalStateException("CLIP model is not initialized. Please configure model paths in application.properties");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        // 1. 保存查询图片到临时文件
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "kbs_search_images");
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        Path tempFilePath = tempDir.resolve(UUID.randomUUID().toString() + "_" + file.getOriginalFilename());
        
        try {
            file.transferTo(tempFilePath.toFile());
            log.info("Temporary search image saved to: {}", tempFilePath);

            // 2. 计算查询图片的向量
            Image queryImage = Image.builder().url(tempFilePath.toUri().toString()).build();
            log.info("Calculating CLIP embedding for image...");
            Embedding queryEmbedding = clipEmbeddingModel.embed(queryImage).content();

            // 3. 在 Milvus 中检索
            log.info("Searching in Milvus image store...");
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(5)
                    .minScore(0.5) // 适当调低阈值以获取更多候选
                    .build();

            EmbeddingSearchResult<TextSegment> result = imageEmbeddingStore.search(searchRequest);
            log.info("Search finished. Found {} matches.", result.matches().size());

            // 4. 返回结果
            return result.matches().stream()
                    .map(match -> Map.<String, Object>of(
                            "score", match.score(),
                            "image_path", match.embedded().text(), 
                            "description", match.embedded().metadata().getString("description") != null ? 
                                           match.embedded().metadata().getString("description") : ""
                    ))
                    .collect(Collectors.toList());
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
     * 5. 以文搜图 (Text to Image Search) - Demo
     * API: GET /api/images/search-by-text
     * 逻辑: 输入文字 -> 计算 CLIP 向量 -> 搜索图片
     */
    @Operation(summary = "以文搜图", description = "输入文字描述（如'一只猫'），搜索最相似的图片")
    @GetMapping("/search-by-text")
    public List<Map<String, Object>> searchImageByText(
            @Parameter(description = "搜索关键词", required = true)
            @RequestParam("query") String query
    ) {
        if (clipEmbeddingModel == null) {
            throw new IllegalStateException("CLIP model is not initialized. Please configure model paths in application.properties");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        log.info("Text-to-image search query: {}", query);

        // 1. 计算文本的 CLIP 向量
        log.info("Calculating CLIP embedding for text...");
        Embedding queryEmbedding = clipEmbeddingModel.embed(query).content();

        // 2. 在 Milvus 中检索
        log.info("Searching in Milvus image store by text...");
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .minScore(0.1) // CLIP 文本-图像相似度得分通常较低，适当降低阈值
                .build();
        
        EmbeddingSearchResult<TextSegment> result = imageEmbeddingStore.search(searchRequest);
        log.info("Search finished. Found {} matches.", result.matches().size());

        return result.matches().stream()
                .map(match -> Map.<String, Object>of(
                        "score", match.score(),
                        "image_path", match.embedded().text(),
                        "description", match.embedded().metadata().getString("description") != null ? 
                                       match.embedded().metadata().getString("description") : ""
                ))
                .collect(Collectors.toList());
    }
}
