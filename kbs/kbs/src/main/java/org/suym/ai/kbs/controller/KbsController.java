package org.suym.ai.kbs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
 * 知识库业务 Controller (Spring AI 版)
 * 包含文档上传 (Ingestion) 和 智能问答 (Retrieval) 接口
 */
@RestController
@RequestMapping("/api")
@Tag(name = "知识库管理", description = "提供文档上传、知识库构建及智能问答相关接口")
public class KbsController {

    private static final Logger log = LoggerFactory.getLogger(KbsController.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public KbsController(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 1. 知识库管理 (RAG - Ingestion)
     * API: POST /api/documents/upload
     */
    @Operation(summary = "上传文档", description = "上传文本文件 (.txt)，系统自动进行分段、向量化并存入知识库")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult<String> uploadDocument(
            @Parameter(description = "需要上传的文本文件", required = true) 
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (file.isEmpty()) {
            return JsonResult.fail("File is empty");
        }

        // 1. 读取文件内容
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        
        // 【优化】手动追加一个换行符，防止最后一行因为切分器边界问题被丢弃
        if (!content.endsWith("\n")) {
            content += "\n";
        }
        
        String filename = file.getOriginalFilename();

        // 2. 创建 Document
        Document document = new Document(content, Map.of("filename", filename));

        // 3. 文档切分 (Chunking)
        // 使用更适合中文的切分策略，例如 TokenTextSplitter 并调整参数
        // 这里的 1000 是 chunk size, 400 是 overlap
        // TokenTextSplitter 可能会吃掉最后的一点内容，如果它不足以构成一个 token
        // 这里改用 SimpleTextSplitter 可能会更稳妥，或者调整 minChunkSize
        TokenTextSplitter splitter = new TokenTextSplitter(1000, 400, 5, 10000, true);
        List<Document> segments = splitter.apply(List.of(document));

        // 4. 向量化并持久化 (Store)
        vectorStore.add(segments);

        return JsonResult.ok("Successfully uploaded " + filename + ". Created " + segments.size() + " segments.");
    }

    /**
     * 2. 智能问答 (RAG - Retrieval)
     * API: POST /api/chat
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

        // 1. 语义检索 (Semantic Search)
        log.info("Starting Vector Store search...");
        List<Document> relevantSegments = vectorStore.similaritySearch(
                SearchRequest.query(question).withTopK(5) // 增加检索数量，确保包含足够上下文
        );
        log.debug("Search finished. Found {} segments.", relevantSegments.size());

        // 2. 构建 Context
        String context = relevantSegments.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n"));

        // 3. 构建 Prompt 并调用 LLM
        String systemText = "你是一个智能助手。请基于以下上下文回答用户的问题。如果上下文中没有答案，请诚实地说不知道。";
        String userText = "上下文:\n" + context + "\n\n" + "问题: " + question;

        log.info("Calling LLM...");
        try {
            String answer = chatClient.prompt()
                    .system(systemText)
                    .user(userText)
                    .call()
                    .content();
            
            log.info("LLM returned answer.");

            // 4. 返回结果
            List<String> sourceDocs = relevantSegments.stream()
                    .map(doc -> (String) doc.getMetadata().get("filename"))
                    .distinct()
                    .collect(Collectors.toList());

            ChatResultVO resultVO = ChatResultVO.builder()
                    .answer(answer)
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

        try {
            vectorStore.delete(Collections.singletonList(id));
            log.info("Deleted from vector store: {}", id);
        } catch (Exception e) {
            log.warn("Failed to delete from vector store: {}", e.getMessage());
            return JsonResult.fail("Failed to delete: " + e.getMessage());
        }

        return JsonResult.ok("Delete operation executed for ID: " + id);
    }
}
