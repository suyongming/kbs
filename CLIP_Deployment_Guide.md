# CLIP 本地部署实战攻略 (Java 版)

本攻略旨在指导你如何在 Java (LangChain4j) 环境下本地部署 CLIP 多模态模型，实现真正的**自然语言搜商品**和**以图搜图**。

***

## 1. 核心原理

CLIP (Contrastive Language-Image Pre-training) 由 OpenAI 发布。它包含两个部分：

- **Text Encoder**: 将文字转换为向量。
- **Vision Encoder**: 将图片转换为向量。
- **关键点**: 它的训练目标是让“猫”这个词的向量，和“猫的图片”的向量，在空间中尽可能接近。

***

## 2. 准备模型文件 (ONNX 格式)

由于我们在 Java 中使用，最方便的方式是使用 **ONNX Runtime**。你需要准备以下三个文件：

### A. 下载途径

推荐从 Hugging Face 下载已经转换好的 ONNX 模型：

- **模型推荐**: `Xenova/clip-vit-base-patch32` (性能与速度平衡最佳)
- **下载地址**: [Hugging Face - Xenova/clip-vit-base-patch32](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/main)

### B. 必须下载的文件

请在 `Xenova/clip-vit-base-patch32` 的文件列表中下载：

1. **Text Model**:
   - 文件名: `text_model.onnx`
   - (或者 `text_model_quantized.onnx` 如果想用量化版)
2. **Vision Model**:
   - 文件名: `vision_model.onnx`
   - (或者 `vision_model_quantized.onnx`)
3. **Tokenizer Config**:
   - 文件名: `tokenizer.json`
   - 文件名: `tokenizer_config.json` (可选，通常 tokenizer.json 就够了)
4. **Preprocessor Config**:
   - 文件名: `preprocessor_config.json`
   - **注意**: 如果你在文件列表中没找到这个单独的文件，它通常被合并在了配置中。对于 CLIP ViT-B/32，你可以直接使用以下标准参数：
     - Resize: `224x224`
     - Mean: `[0.48145466, 0.4578275, 0.40821073]`
     - Std: `[0.26862954, 0.26130258, 0.27577711]`

### C. 存放路径建议

在你的项目目录下创建 `models/clip` 文件夹：

```text
kbs/
  models/
    clip/
      text_model.onnx
      vision_model.onnx
      tokenizer.json
```

***

## 3. 修改项目依赖 (`pom.xml`)

为了在 Java 中处理图片（缩放、归一化）并运行 ONNX，你需要添加以下依赖：

```xml
<!-- ONNX Runtime (用于推理) -->
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.19.0</version>
</dependency>

<!-- 图像处理库 (用于图片预处理) -->
<dependency>
    <groupId>com.twelvemonkeys.imageio</groupId>
    <artifactId>imageio-core</artifactId>
    <version>3.12.0</version>
</dependency>
```

***

## 4. 配置 `application.yml`

将下载的文件路径配置到项目中：

```yaml
clip:
  text_model:
    path: "C:/path/to/models/clip/text_model.onnx"
    tokenizer_path: "C:/path/to/models/clip/tokenizer.json"
  vision_model:
    path: "C:/path/to/models/clip/vision_model.onnx"
```

***

## 5. 接口实现逻辑变更 (后续步骤)

部署完模型后，我们将重写 `ClipEmbeddingModel.java`。真实的逻辑如下：

1. **文本嵌入**: 调用 `text_model.onnx`。
2. **图片嵌入**:
   - 读取图片文件。
   - **预处理**: 将图片 Resize 到 `224x224`，并进行 `Normalize` (减去均值，除以标准差)。
   - **推理**: 将处理后的像素矩阵 (Tensor) 喂给 `vision_model.onnx`。
   - **输出**: 获得 512 维的图片向量。

***

## 6. 常见坑点

- **内存占用**: CLIP 模型较大，建议给 JVM 分配至少 4GB 内存 (`-Xmx4g`)。
- **维度匹配**: 确保 Text 和 Vision 模型输出的维度一致（ViT-B-32 通常是 512 维）。
- **预处理参数**: CLIP 对预处理非常敏感，均值和标准差必须严格按照模型要求设置，否则搜索效果会很差。

***

**准备好了吗？如果你下载好了模型文件并配置了路径，请告诉我，我们立刻开始重写代码实现！**
