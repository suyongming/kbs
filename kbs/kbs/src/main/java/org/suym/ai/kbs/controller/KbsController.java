package org.suym.ai.kbs.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.data.image.Image;

import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库业务 Controller
 * 包含文档上传 (Ingestion) 和 智能问答 (Retrieval) 接口
 * 以及新增的以图搜图 (Image Search) 接口 Demo
 */
@RestController
@RequestMapping("/api")
@Tag(name = "知识库管理", description = "提供文档上传、知识库构建及智能问答相关接口")
public class KbsController {

    private static final Logger log = LoggerFactory.getLogger(KbsController.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    
    // 图片向量库 (存储图片向量)
    private final EmbeddingStore<TextSegment> imageEmbeddingStore;
    
    // CLIP 模型 (用于生成图片和文本的向量)
    private final ClipEmbeddingModel clipEmbeddingModel;

    public KbsController(@Qualifier("milvusEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore, 
                         EmbeddingModel embeddingModel,
                         ChatLanguageModel chatLanguageModel,
                         @Qualifier("imageEmbeddingStore") EmbeddingStore<TextSegment> imageEmbeddingStore,
                         @Autowired(required = false) ClipEmbeddingModel clipEmbeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
        this.imageEmbeddingStore = imageEmbeddingStore;
        this.clipEmbeddingModel = clipEmbeddingModel;
    }

    /**
     * 1. 知识库管理 (RAG - Ingestion)
     * API: POST /api/documents/upload
     * 逻辑: 接收文件 -> 解析 -> 切分 -> 向量化 -> 持久化
     */
    @Operation(summary = "上传文档", description = "上传文本文件 (.txt)，系统自动进行分段、向量化并存入 Milvus 知识库")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadDocument(
            @Parameter(description = "需要上传的文本文件", required = true) 
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (file.isEmpty()) {
            return "File is empty";
        }

        // 1. 读取文件内容 (MVP 仅支持文本文件)
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String filename = file.getOriginalFilename();

        // 2. 创建 Document 对象并添加元数据
        Document document = Document.from(content, Metadata.from("filename", filename));

        // 3. 文档切分 (Chunking)
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 0);
        List<TextSegment> segments = splitter.split(document);

        // 4. 向量化 (Embedding) 并 5. 持久化 (Store)
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        return "Successfully uploaded " + filename + ". Created " + segments.size() + " segments.";
    }

    /**
     * 2. 智能问答 (RAG - Retrieval)
     * API: POST /api/chat
     * 逻辑: 问题向量化 -> 语义检索 -> 构建 Prompt -> 调用 LLM -> 返回结果
     */
    @Operation(summary = "智能问答", description = "基于已上传的知识库文档回答用户问题")
    @ApiResponse(responseCode = "200", description = "成功返回回答", 
            content = @Content(schema = @Schema(example = "{\"answer\": \"...\", \"source_docs\": [\"file1.txt\"]}")))
    @PostMapping("/chat")
    public Map<String, Object> chat(
            @Parameter(description = "包含问题的 JSON 对象，例如 {\"question\": \"...\"}", required = true)
            @RequestBody Map<String, String> request
    ) {
        log.info("Entering /api/chat");
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            log.info("Question is empty");
            throw new IllegalArgumentException("Question cannot be empty");
        }

        log.info("Question received: {}", question);

        // 1. 将用户问题向量化
        log.info("Starting embedding...");
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        log.info("Embedding finished.");

        // 2. 语义检索 (Semantic Search)
        log.info("Starting Milvus search...");
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(3)
                .minScore(0.0)
                .build();
        
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<TextSegment> relevantSegments = searchResult.matches().stream()
                .map(match -> match.embedded())
                .collect(Collectors.toList());
        log.debug("Milvus search finished. Found {} segments.", relevantSegments.size());

        // 3. 构建 Prompt
        String context = relevantSegments.stream()
                .map(TextSegment::text)
                .collect(Collectors.joining("\n\n"));

        // 可能会有幻觉
        String prompt = "基于以下上下文回答问题:\n\n" +
                "上下文:\n" + context + "\n\n" +
                "问题: " + question;

        // 完全降低幻觉
//        String prompt = "基于以下上下文回答问题,如果你在上下文中找不到答案，请直接回答‘知识库中未找到相关信息’，不要编造答案。:\n\n" +
//                "上下文:\n" + context + "\n\n" +
//                "问题: " + question;

        log.info("Prompt built. Length: {}", prompt.length());

        // 4. 生成回答 (Generation)
        log.info("Calling LLM...");
        try {
            AiMessage answer = chatLanguageModel.generate(UserMessage.from(prompt)).content();
            log.info("LLM returned answer.");

            // 5. 返回结果
            List<String> sourceDocs = relevantSegments.stream()
                    .map(segment -> segment.metadata().getString("filename"))
                    .distinct()
                    .collect(Collectors.toList());

            return Map.of(
                    "answer", answer.text(),
                    "source_docs", sourceDocs
            );
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 3. 图片上传与索引 (Image Ingestion) - Demo
     * API: POST /api/images/upload
     * 逻辑: 上传图片 -> 保存到本地/云存储 -> 计算 CLIP 向量 -> 存入 Milvus
     */
    @Operation(summary = "上传图片 (以图搜图)", description = "上传图片，计算 CLIP 向量并存入图库")
    @PostMapping(value = "/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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
    @PostMapping(value = "/images/search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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
    @GetMapping("/images/search-by-text")
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

    /**
     * 6. 根据 ID 删除数据
     * API: DELETE /api/documents/{id}
     * 逻辑: 直接调用 EmbeddingStore 的 removeAll 方法删除指定 ID 的记录
     */
    @Operation(summary = "删除文档/数据", description = "根据 ID 删除知识库中的数据")
    @DeleteMapping("/documents/{id}")
    public String deleteDocument(
            @Parameter(description = "需要删除的数据 ID", required = true)
            @PathVariable("id") String id
    ) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("ID cannot be empty");
        }

        log.info("Deleting document with ID: {}", id);

        // 尝试从文本知识库中删除
        try {
            embeddingStore.removeAll(Collections.singletonList(id));
            log.info("Deleted from text embedding store: {}", id);
        } catch (Exception e) {
            log.warn("Failed to delete from text embedding store: {}", e.getMessage());
        }

        // 尝试从图片知识库中删除 (如果是图片 ID)
        try {
            imageEmbeddingStore.removeAll(Collections.singletonList(id));
            log.info("Deleted from image embedding store: {}", id);
        } catch (Exception e) {
            log.warn("Failed to delete from image embedding store: {}", e.getMessage());
        }

        return "Delete operation executed for ID: " + id;
    }
}
