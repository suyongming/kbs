package org.suym.ai.kbs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.suym.ai.kbs.dto.base.JsonResult;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;
import org.suym.ai.kbs.vo.ImageSearchVO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
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

    private final VectorStore vectorStore;
    private final ClipEmbeddingModel clipEmbeddingModel;

    public ImageController(VectorStore vectorStore,
                           ClipEmbeddingModel clipEmbeddingModel) {
        this.vectorStore = vectorStore;
        this.clipEmbeddingModel = clipEmbeddingModel;
    }

    @Operation(summary = "上传图片 (以图搜图)", description = "上传图片，计算 CLIP 向量并存入图库")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult<String> uploadImage(
            @Parameter(description = "需要上传的图片文件", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "图片描述（可选，用于元数据）")
            @RequestParam(value = "description", required = false, defaultValue = "") String description
    ) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Invalid file type. Only images are allowed.");
        }

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
        
        try {
            file.transferTo(tempFilePath.toFile());
            log.info("Image saved to: {}", tempFilePath);

            // 创建 Document，内容为图片路径
            // ClipEmbeddingModel 会识别并计算 Image Embedding
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("description", description);
            metadata.put("type", "gallery"); // 标记为图库数据

            Document doc = new Document(tempFilePath.toString(), metadata);
            vectorStore.add(List.of(doc));

            return JsonResult.ok("Image uploaded successfully. ID: " + uniqueFilename);
        } catch (IOException e) {
            log.error("Failed to upload image", e);
            throw e; // Let global handler catch it or rethrow
        }
        // 注意：这里我们不删除文件，因为上传接口通常意味着持久化。
        // 实际生产中应该上传到 OSS，这里作为 Demo 存在本地临时目录。
    }

    @Operation(summary = "以图搜图", description = "上传一张图片，在图库中搜索最相似的图片")
    @PostMapping(value = "/search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult<List<ImageSearchVO>> searchImage(
            @Parameter(description = "用于搜索的图片", required = true)
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Invalid file type. Only images are allowed.");
        }

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "kbs_search_images");
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        Path tempFilePath = tempDir.resolve(UUID.randomUUID().toString() + "_" + file.getOriginalFilename());
        
        try {
            file.transferTo(tempFilePath.toFile());
            log.info("Temporary search image saved to: {}", tempFilePath);

            // 直接搜索图片路径，CLIP 会自动嵌入
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.query(tempFilePath.toString()).withTopK(5)
            );

            List<ImageSearchVO> resultList = results.stream()
                    .map(doc -> {
                        Double distance = 0.0;
                        Object distObj = doc.getMetadata().get("distance");
                        if (distObj instanceof Float) {
                            distance = ((Float) distObj).doubleValue();
                        } else if (distObj instanceof Double) {
                            distance = (Double) distObj;
                        }
                        
                        return ImageSearchVO.builder()
                            .score(1.0 - distance)
                            .imagePath(doc.getContent())
                            .description((String) doc.getMetadata().get("description"))
                            .build();
                    })
                    .collect(Collectors.toList());
            
            return JsonResult.ok(resultList);
        } finally {
            try {
                Files.deleteIfExists(tempFilePath);
            } catch (IOException e) {
                log.warn("Failed to delete temporary search image: {}", tempFilePath, e);
            }
        }
    }

    @Operation(summary = "以文搜图", description = "输入文字描述（如'一只猫'），搜索最相似的图片")
    @GetMapping("/search-by-text")
    public JsonResult<List<ImageSearchVO>> searchImageByText(
            @Parameter(description = "搜索关键词", required = true)
            @RequestParam("query") String query
    ) {
        log.info("Text-to-image search query: {}", query);

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(5)
        );

        List<ImageSearchVO> resultList = results.stream()
                .map(doc -> {
                    Double distance = 0.0;
                    Object distObj = doc.getMetadata().get("distance");
                    if (distObj instanceof Float) {
                        distance = ((Float) distObj).doubleValue();
                    } else if (distObj instanceof Double) {
                        distance = (Double) distObj;
                    }

                    return ImageSearchVO.builder()
                        .score(1.0 - distance)
                        .imagePath(doc.getContent())
                        .description((String) doc.getMetadata().get("description"))
                        .build();
                })
                .collect(Collectors.toList());

        return JsonResult.ok(resultList);
    }
}
