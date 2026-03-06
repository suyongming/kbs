# 实战 Demo 需求：企业级文档问答助手 (Enterprise Doc QA) - Milvus 版

## 🎯 项目目标
构建一个基于 **Spring Boot + Milvus** 的后端服务，允许用户上传公司内部的技术文档，并通过 AI 助手进行基于文档的智能问答。

---

## 🛠️ 技术栈要求
*   **语言**: Java 17+
*   **框架**: Spring Boot 3.x
*   **AI 核心**: LangChain4j (配合 `langchain4j-spring-boot-starter`)
*   **数据库**:
    *   **向量库**: **Milvus** (使用 Docker 部署) —— *云原生、高性能，企业级 RAG 首选。*
    *   **业务库**: 内存或简单的 H2/SQLite (用于存文件名等非向量元数据，可选)。
*   **大模型**: OpenAI 兼容接口 (推荐 DeepSeek / Moonshot)。
*   **可视化工具**: **Attu** (Milvus 官方管理工具，类似 Navicat)。

---

## 📝 功能需求 (MVP 版本)

### 1. 知识库管理 (RAG - Ingestion)
*   **API**: `POST /api/documents/upload`
*   **输入**: `MultipartFile` (支持 .txt, .md 文件)
*   **逻辑**:
    1.  接收文件。
    2.  解析文本内容。
    3.  **文档切分 (Chunking)**：按段落或固定字符数（如 500字）切分。
    4.  **向量化 (Embedding)**：调用 Embedding 模型生成向量。
    5.  **持久化**: 将 文本片段 + 向量 存入 Milvus 的 `Collection` (类似于 SQL 中的 Table)。

### 2. 智能问答 (RAG - Retrieval)
*   **API**: `POST /api/chat`
*   **输入**: `{ "question": "公司的报销流程是怎样的？" }`
*   **逻辑**:
    1.  将用户问题向量化。
    2.  **语义检索**: 在 Milvus 中执行 `search` 操作，查找最相似的 3-5 个向量片段。
    3.  **构建 Prompt**: `上下文: [检索到的片段] + 问题: [用户问题]`。
    4.  **生成回答**: 调用 LLM 生成最终答案。
    5.  **返回**: `{ "answer": "...", "source_docs": ["文件名1", "文件名2"] }`。

---

## 📅 Milvus 专项开发计划 (预计耗时：10-16 小时)

### 第一阶段：环境与基础设施 (2-4 小时)

#### 1. Windows 安装 Docker Desktop
*   **步骤**:
    1.  访问 [Docker 官网](https://www.docker.com/products/docker-desktop/) 下载 Windows 版安装包。
    2.  安装并启动，确保左下角状态为绿色 (Running)。
    3.  *注意*: 需要开启 WSL 2 (Windows Subsystem for Linux)，Docker 安装程序通常会提示并辅助你开启。

#### 2. Docker 安装 Milvus (单机版)
*   **步骤**:
    1.  下载官方的 `docker-compose.yml` 文件:
        ```bash
        wget https://github.com/milvus-io/milvus/releases/download/v2.3.4/milvus-standalone-docker-compose.yml -O docker-compose.yml
        ```
        *(如果没有 wget，可以直接在浏览器打开链接另存为)*
    2.  启动 Milvus:
        ```bash
        docker compose up -d
        ```
    3.  验证: `docker ps` 能看到 `milvus-standalone`, `milvus-etcd`, `milvus-minio` 三个容器在运行。

#### 3. 安装可视化工具 Attu (类似 Navicat)
*   **步骤**:
    1.  直接用 Docker 启动 Attu:
        ```bash
        docker run -p 8000:3000 -e MILVUS_URL=host.docker.internal:19530 zilliz/attu:v2.3.4
        ```
    2.  访问浏览器 `http://localhost:8000`。
    3.  连接 Milvus: 地址填 `host.docker.internal` (或本机IP)，端口 `19530`。
    4.  **创建 Collection (建表)**:
        *   在 Attu 界面点击 "Create Collection"。
        *   Name: `enterprise_knowledge`
        *   **Schema 设计**:
            *   `id` (Int64, Primary Key, AutoID=True)
            *   `embedding` (FloatVector, Dim=384) -> *注意: 维度要和你的 Embedding 模型一致，AllMiniLmL6V2 是 384*
            *   `text` (VarChar, MaxLength=65535) -> *用来存文本内容*
            *   `filename` (VarChar, MaxLength=255)

### 第二阶段：Spring Boot 集成 (4-6 小时)
1.  **初始化项目**: 引入 `langchain4j-milvus` 依赖。
2.  **配置连接**: 在 `application.yml` 中配置 Milvus 地址。
3.  **实现 Embedding 流程**:
    *   使用 LangChain4j 的 `MilvusEmbeddingStore` 类，它封装了底层繁琐的 SDK 调用。
    *   测试: 存入一条 "Hello World"，在 Attu 中查看是否新增了一条数据。

### 第三阶段：业务逻辑与 API (3-4 小时)
1.  **开发 Upload 接口**: 接收文件 -> 切分 -> `embeddingStore.add(segments)`。
2.  **开发 Chat 接口**: `embeddingStore.findRelevant(query, maxResults)` -> 组装 Prompt -> 调用 LLM。
3.  **调试**: 上传真实文档，观察检索准确率。

### 第四阶段：优化 (2 小时)
1.  **元数据过滤**: 比如只搜 "2024年" 的文档 (Milvus 支持标量过滤)。
2.  **索引优化**: 在 Attu 中为 `embedding` 字段创建索引 (如 `IVF_FLAT`)，加速查询。

---

## ✅ 验收标准 (Definition of Done)
1.  Docker 中运行着 Milvus 和 Attu。
2.  浏览器打开 Attu (`localhost:8000`) 能看到 `enterprise_knowledge` 集合。
3.  Postman 上传文档后，Attu 里能看到数据行数增加。
4.  Postman 提问能得到基于文档的回复。
