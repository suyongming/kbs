package org.suym.ai.kbs.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.suym.ai.kbs.model.embedding.ClipEmbeddingModel;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.context.annotation.Primary;

/**
 * Embedding 配置类
 * 负责配置 Embedding 模型和 Milvus 向量数据库连接
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    // 从配置文件中读取 Milvus 的主机地址
    @Value("${milvus.host}")
    private String host;

    // 从配置文件中读取 Milvus 的端口号
    @Value("${milvus.port}")
    private int port;

    // 从配置文件中读取 Milvus 的集合名称
    @Value("${milvus.collection-name}")
    private String collectionName;

    // 从配置文件中读取 Embedding 向量的维度 (AllMiniLmL6V2 为 384)
    @Value("${milvus.dimension}")
    private int dimension;

    @Value("${clip.text_model.path:}")
    private String clipTextModelPath;

    @Value("${clip.text_tokenizer.path:}")
    private String clipTextTokenizerPath;

    @Value("${clip.vision_model.path:}")
    private String clipVisionModelPath;

    @Value("${clip.vision_tokenizer.path:}")
    private String clipVisionTokenizerPath;

    /**
     * 配置文本 Embedding 模型 Bean
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    /**
     * 配置多模态 (CLIP) Embedding 模型 Bean
     * 使用自定义的 ClipEmbeddingModel 包装类
     */
    @Bean
    public ClipEmbeddingModel clipEmbeddingModel() {
        if (clipTextModelPath == null || clipTextModelPath.isEmpty()) {
            // 如果未配置，返回 null 或抛出异常，建议用户下载模型
            log.warn("CLIP models not configured. Image search will be disabled.");
            return null;
        }
        return new ClipEmbeddingModel(
            clipTextModelPath, clipTextTokenizerPath,
            clipVisionModelPath, clipVisionTokenizerPath
        );
    }

    /**
     * 配置 Milvus Embedding Store Bean (文本知识库)
     */
    @Bean
    @Primary
    public EmbeddingStore<TextSegment> milvusEmbeddingStore() {
        // ... (保持原有文本 Store 逻辑)
        String vectorFieldName = getFieldValue("VECTOR_FIELD_NAME", "vector");
        String idFieldName = getFieldValue("ID_FIELD_NAME", "id");
        String textFieldName = getFieldValue("TEXT_FIELD_NAME", "text");
        String metadataFieldName = getFieldValue("METADATA_FIELD_NAME", "metadata");

        setupMilvus(host, port, collectionName, dimension, idFieldName, vectorFieldName, textFieldName, metadataFieldName);
        
        return MilvusEmbeddingStore.builder()
                .uri("http://" + host + ":" + port)
                .collectionName(collectionName)
                .dimension(dimension)
                .retrieveEmbeddingsOnSearch(true)
                .build();
    }

    /**
     * 配置 Milvus Embedding Store Bean (图片知识库)
     * 使用不同的集合名称和维度 (512)
     */
    @Bean
    public EmbeddingStore<TextSegment> imageEmbeddingStore() {
        String collectionName = "image_gallery_v1";
        int dimension = 512; // CLIP ViT-B/32 维度
        
        String vectorFieldName = "vector";
        String idFieldName = "id";
        String textFieldName = "text"; // 这里存图片描述或路径
        String metadataFieldName = "metadata";

        setupMilvus(host, port, collectionName, dimension, idFieldName, vectorFieldName, textFieldName, metadataFieldName);

        return MilvusEmbeddingStore.builder()
                .uri("http://" + host + ":" + port)
                .collectionName(collectionName)
                .dimension(dimension)
                .retrieveEmbeddingsOnSearch(true)
                .build();
    }

    /**
     * 辅助方法：通过反射获取类的静态字段值
     */
    private String getFieldValue(String fieldName, String defaultValue) {
        try {
            java.lang.reflect.Field field = MilvusEmbeddingStore.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 初始化 Milvus 集合和索引
     * 如果集合不存在，则创建集合；如果索引不存在，则创建索引
     */
    private void setupMilvus(String host, int port, String collectionName, int dimension, 
                             String idFieldName, String vectorFieldName, String textFieldName, String metadataFieldName) {
        // 创建 Milvus 客户端
        MilvusServiceClient client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .build()
        );

        try {
            // 检查集合是否存在
            boolean hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build()).getData();

            if (!hasCollection) {
                // 定义 ID 字段：字符串类型，最大长度 36，主键，非自动生成 (由 LangChain4j 生成 UUID)
                FieldType id = FieldType.newBuilder()
                        .withName(idFieldName)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(36)
                        .withPrimaryKey(true)
                        .withAutoID(false) 
                        .build();

                // 定义向量字段：浮点向量类型，指定维度
                FieldType embedding = FieldType.newBuilder()
                        .withName(vectorFieldName)
                        .withDataType(DataType.FloatVector)
                        .withDimension(dimension)
                        .build();

                // 定义文本字段：字符串类型，用于存储原始文本内容
                FieldType text = FieldType.newBuilder()
                        .withName(textFieldName)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build();
                
                // 定义元数据字段：JSON 类型，用于存储额外的元数据
                FieldType metadata = FieldType.newBuilder()
                        .withName(metadataFieldName)
                        .withDataType(DataType.JSON) 
                        .build();

                // 创建集合参数
                CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .addFieldType(id)
                        .addFieldType(embedding)
                        .addFieldType(text)
                        .addFieldType(metadata)
                        .build();

                // 执行创建集合操作
                client.createCollection(createParam);
                System.out.println("Collection " + collectionName + " created.");
            } else {
                 System.out.println("Collection " + collectionName + " exists.");
            }

            // 创建索引 (如果需要)
            try {
                // 定义索引参数：使用 IVF_FLAT 索引类型，COSINE (余弦相似度) 度量类型
                CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName(vectorFieldName)
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam("{\"nlist\":1024}")
                        .build();

                client.createIndex(indexParam);
                System.out.println("Index created/ensured on " + collectionName);
            } catch (Exception e) {
                System.out.println("Index creation might have failed or already exists: " + e.getMessage());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to setup Milvus collection", e);
        } finally {
            // 关闭客户端连接
            client.close();
        }
    }
}
