package org.suym.ai.kbs.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;

/**
 * Embedding & Vector Store Configuration
 * 负责配置 Embedding 模型和 Milvus 向量数据库连接
 * 支持多集合配置 (Default / Goods)
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    // --- Milvus Common Config ---
    @Value("${milvus.host}")
    private String milvusHost;

    @Value("${milvus.port}")
    private int milvusPort;

    // --- Default Collection Config (Text Knowledge) ---
    @Value("${milvus.default.collection-name}")
    private String defaultCollectionName;

    @Value("${milvus.default.dimension}")
    private int defaultDimension;

    // --- Goods Collection Config (Image/Multimodal Knowledge) ---
    @Value("${milvus.goods.collection-name}")
    private String goodsCollectionName;

    @Value("${milvus.goods.dimension}")
    private int goodsDimension;

    // --- Gallery Collection Config (Pure Image Search Demo) ---
    @Value("${milvus.gallery.collection-name}")
    private String galleryCollectionName;

    @Value("${milvus.gallery.dimension}")
    private int galleryDimension;

    // --- CLIP Model Config ---
    @Value("${clip.model.text-path:}")
    private String clipTextModelPath;

    @Value("${clip.model.tokenizer-path:}")
    private String clipTokenizerPath;

    @Value("${clip.model.vision-path:}")
    private String clipVisionModelPath;

    /**
     * 1. 默认文本 Embedding 模型 (AllMiniLmL6V2)
     * 用于处理纯文本知识库 (RAG)
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    /**
     * 2. 多模态 CLIP Embedding 模型
     * 用于商品图片/文本检索
     */
    @Bean
    public ClipEmbeddingModel clipEmbeddingModel() {
        if (clipTextModelPath == null || clipTextModelPath.isEmpty()) {
            log.warn("CLIP models not configured. Image search will be disabled.");
            return null;
        }
        return new ClipEmbeddingModel(clipTextModelPath, clipTokenizerPath, clipVisionModelPath);
    }

    /**
     * 3. 默认 Milvus Embedding Store (文本知识库)
     * 对应 enterprise_knowledge_v2
     */
    @Bean
    @Primary
    public EmbeddingStore<TextSegment> milvusEmbeddingStore() {
        ensureMilvusCollection(defaultCollectionName, defaultDimension);
        
        return MilvusEmbeddingStore.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .collectionName(defaultCollectionName)
                .dimension(defaultDimension)
                .retrieveEmbeddingsOnSearch(true)
                .build();
    }

    /**
     * 4. 商品 Milvus Embedding Store (商品知识库)
     * 对应 goods_store_v1
     */
    @Bean
    public EmbeddingStore<TextSegment> goodsEmbeddingStore() {
        ensureMilvusCollection(goodsCollectionName, goodsDimension);

        return MilvusEmbeddingStore.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .collectionName(goodsCollectionName)
                .dimension(goodsDimension)
                .retrieveEmbeddingsOnSearch(true)
                .build();
    }

    /**
     * 5. 图库 Milvus Embedding Store (通用图片库)
     * 对应 image_gallery_v1
     */
    @Bean
    public EmbeddingStore<TextSegment> galleryEmbeddingStore() {
        ensureMilvusCollection(galleryCollectionName, galleryDimension);

        return MilvusEmbeddingStore.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .collectionName(galleryCollectionName)
                .dimension(galleryDimension)
                .retrieveEmbeddingsOnSearch(true)
                .build();
    }

    /**
     * 辅助方法：确保 Milvus 集合和索引存在
     */
    private void ensureMilvusCollection(String collectionName, int dimension) {
        MilvusServiceClient client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(milvusHost)
                        .withPort(milvusPort)
                        .build()
        );

        try {
            boolean hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build()).getData();

            if (!hasCollection) {
                log.info("Creating Milvus collection: {}", collectionName);
                
                // Define Fields
                FieldType id = FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(36)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build();

                FieldType embedding = FieldType.newBuilder()
                        .withName("vector")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dimension)
                        .build();

                FieldType text = FieldType.newBuilder()
                        .withName("text")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build();

                FieldType metadata = FieldType.newBuilder()
                        .withName("metadata")
                        .withDataType(DataType.JSON)
                        .build();

                CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .addFieldType(id)
                        .addFieldType(embedding)
                        .addFieldType(text)
                        .addFieldType(metadata)
                        .build();

                client.createCollection(createParam);
                
                // Create Index
                CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("vector")
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam("{\"nlist\":1024}")
                        .build();

                client.createIndex(indexParam);
                log.info("Collection {} and index created successfully.", collectionName);
            } else {
                log.info("Collection {} already exists.", collectionName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure Milvus collection: {}", collectionName, e);
            throw new RuntimeException("Milvus setup failed", e);
        } finally {
            client.close();
        }
    }
}
