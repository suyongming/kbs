package org.suym.ai.kbs.controller;

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
import org.suym.ai.kbs.dto.base.JsonResult;
import org.suym.ai.kbs.vo.ChatResultVO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库业务 Controller
 * 包含文档上传 (Ingestion) 和 智能问答 (Retrieval) 接口
 */
@RestController
@RequestMapping("/api")
@Tag(name = "知识库管理", description = "提供文档上传、知识库构建及智能问答相关接口")
public class KbsController {

    private static final Logger log = LoggerFactory.getLogger(KbsController.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;

    public KbsController(@Qualifier("milvusEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore, 
                         EmbeddingModel embeddingModel,
                         ChatLanguageModel chatLanguageModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 1. 知识库管理 (RAG - Ingestion)
     * API: POST /api/documents/upload
     * 逻辑: 接收文件 -> 解析 -> 切分 -> 向量化 -> 持久化
     */
    @Operation(summary = "上传文档", description = "上传文本文件 (.txt)，系统自动进行分段、向量化并存入 Milvus 知识库")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult<String> uploadDocument(
            @Parameter(description = "需要上传的文本文件", required = true) 
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (file.isEmpty()) {
            return JsonResult.fail("File is empty");
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

        return JsonResult.ok("Successfully uploaded " + filename + ". Created " + segments.size() + " segments.");
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
    public JsonResult<ChatResultVO> chat(
            @Parameter(description = "包含问题的 JSON 对象，例如 {\"question\": \"...\"}", required = true)
            @RequestBody Map<String, String> request) {

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

            ChatResultVO resultVO = ChatResultVO.builder()
                    .answer(answer.text())
                    .sourceDocs(sourceDocs)
                    .build();

            return JsonResult.ok(resultVO);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 6. 根据 ID 删除数据
     * API: DELETE /api/documents/{id}
     * 逻辑: 直接调用 EmbeddingStore 的 removeAll 方法删除指定 ID 的记录
     */
    @Operation(summary = "删除文档/数据", description = "根据 ID 删除知识库中的数据")
    @DeleteMapping("/documents/{id}")
    public JsonResult<String> deleteDocument(
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
            return JsonResult.fail("Failed to delete: " + e.getMessage());
        }

        return JsonResult.ok("Delete operation executed for ID: " + id);
    }
}
