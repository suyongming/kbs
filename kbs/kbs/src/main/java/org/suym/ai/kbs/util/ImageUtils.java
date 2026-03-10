package org.suym.ai.kbs.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Image processing utility class for CLIP models
 * 重写版：专注于健壮性和兼容性
 */
public class ImageUtils {

    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    // CLIP ViT-B/32 Standard Normalization Constants
    private static final int TARGET_SIZE = 224;
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] STD = {0.26862954f, 0.26130258f, 0.27577711f};

    /**
     * Load image from local path or HTTP/HTTPS URL
     * 增强了异常处理和流关闭，并增加了 Toolkit 降级加载机制
     */
    public static BufferedImage loadImage(String urlOrPath) throws Exception {
        if (urlOrPath == null || urlOrPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Image path is empty");
        }

        byte[] imageBytes = null;

        // 1. Download/Read bytes first
        if (urlOrPath.startsWith("http")) {
            URL url = new URL(urlOrPath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Referer", "https://www.google.com/"); // Fake referer
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setRequestProperty("Sec-Fetch-Dest", "image");
            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors");
            connection.setRequestProperty("Sec-Fetch-Site", "cross-site");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 读取错误流，看看服务器说了什么
                try (InputStream err = connection.getErrorStream()) {
                    if (err != null) {
                        String errContent = new String(err.readAllBytes());
                        log.error("Server returned {} with body: {}", responseCode, errContent);
                    }
                }
                throw new RuntimeException("Failed to download image. HTTP Code: " + responseCode + " for URL: " + urlOrPath);
            }
            
            // Check Content-Type if possible
            String contentType = connection.getContentType();
            log.info("Content-Type: {}", contentType);

            try (InputStream inputStream = connection.getInputStream()) {
                imageBytes = inputStream.readAllBytes();
            }
            
            // 立即检查 Magic Number
            if (imageBytes.length > 0) {
                 // 打印前 8 个字节的十六进制
                 StringBuilder sb = new StringBuilder();
                 for (int i = 0; i < Math.min(8, imageBytes.length); i++) {
                     sb.append(String.format("%02X ", imageBytes[i]));
                 }
                 log.info("Downloaded {} bytes. Header: {}", imageBytes.length, sb.toString());
                 
                 // 如果不是图片头 (FF D8 for JPG, 89 50 for PNG, 47 49 for GIF)
                 // 且看起来像文本 (<)
                 if (imageBytes[0] == 0x3C || imageBytes[0] == 0x7B) { // < or {
                     String content = new String(imageBytes, 0, Math.min(imageBytes.length, 500));
                     log.error("DOWNLOADED TEXT INSTEAD OF IMAGE: {}", content);
                     throw new RuntimeException("Downloaded content is text/html/json, not image");
                 }
            }
        } else {
            // Handle file protocol
            if (urlOrPath.startsWith("file:///")) {
                urlOrPath = urlOrPath.substring(8);
            } else if (urlOrPath.startsWith("file:/")) {
                urlOrPath = urlOrPath.substring(6);
            }
            File file = new File(urlOrPath);
            if (!file.exists()) {
                throw new RuntimeException("File not found: " + urlOrPath);
            }
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                imageBytes = fis.readAllBytes();
            }
        }

        if (imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("Empty image data from: " + urlOrPath);
        }

        // DEBUG: Save the corpse
        try {
            Files.write(Paths.get("C:\\Users\\18699\\Desktop\\debug_image.jpg"), imageBytes);
            log.info("DEBUG: Saved downloaded bytes to desktop.");
        } catch (Exception e) {
            log.error("DEBUG: Failed to save bytes", e);
        }

        // Inspect header for format detection
        if (imageBytes.length >= 12) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02X ", imageBytes[i]));
            }
            log.info("Image Header (hex): {}", sb.toString());
            
            // Check for WebP (RIFF....WEBP)
            if (imageBytes[0] == 0x52 && imageBytes[1] == 0x49 && imageBytes[2] == 0x46 && imageBytes[3] == 0x46 &&
                imageBytes[8] == 0x57 && imageBytes[9] == 0x45 && imageBytes[10] == 0x42 && imageBytes[11] == 0x50) {
                log.warn("Detected WebP format! You need imageio-webp dependency.");
            }
        }

        // 2. Try ImageIO first
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            if (image != null) {
                return image;
            }
        } catch (Exception e) {
            log.warn("ImageIO failed to read image, trying ImageIcon: {}", e.getMessage());
        }

        // 3. Fallback to ImageIcon (simplest AWT loader)
        log.info("Falling back to ImageIcon for image: {}", urlOrPath);
        ImageIcon icon = new ImageIcon(imageBytes);
        
        if (icon.getImageLoadStatus() == MediaTracker.ERRORED || icon.getImageLoadStatus() == MediaTracker.ABORTED) {
            throw new RuntimeException("ImageIcon failed to load image");
        }
        
        Image image = icon.getImage();

        // Convert Image to BufferedImage
        BufferedImage bufferedImage = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bufferedImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        
        return bufferedImage;
    }

    /**
     * Preprocess image: Resize (Shortest Edge) -> Center Crop -> Normalize -> CHW Layout
     * 回滚版：回归最简单的 AWT 绘图，移除所有手动像素操作
     */
    public static float[][][][] preprocessImage(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        // 1. Calculate target dimensions (Resize Shortest Edge to 224)
        int newWidth, newHeight;
        if (width < height) {
            newWidth = TARGET_SIZE;
            newHeight = (int) Math.round((double) height * TARGET_SIZE / width);
        } else {
            newHeight = TARGET_SIZE;
            newWidth = (int) Math.round((double) width * TARGET_SIZE / height);
        }

        // 2. Resize using standard AWT
        // 创建一个标准的 RGB 画布 (兼容所有来源格式)
        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        
        // 使用双线性插值以获得较好的画质
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        
        // 关键：先填充白色背景，处理透明图片 (如 GIF/PNG)
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        
        // 绘制原图 (自动进行颜色空间转换)
        // 这一步是最稳健的，只要 originalImage 能被加载，drawImage 通常都能工作
        g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        g.dispose();

        // 3. Center Crop & Normalize
        int cropX = (newWidth - TARGET_SIZE) / 2;
        int cropY = (newHeight - TARGET_SIZE) / 2;

        float[][][][] data = new float[1][3][TARGET_SIZE][TARGET_SIZE];

        for (int y = 0; y < TARGET_SIZE; y++) {
            for (int x = 0; x < TARGET_SIZE; x++) {
                // 直接从 resize 后的 RGB 图片中取值，无需再做坐标映射
                int rgb = resizedImage.getRGB(x + cropX, y + cropY);

                // Extract RGB components (0-255)
                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float gVal = ((rgb >> 8) & 0xFF) / 255.0f;
                float b = (rgb & 0xFF) / 255.0f;

                // Normalize: (value - mean) / std
                data[0][0][y][x] = (r - MEAN[0]) / STD[0];
                data[0][1][y][x] = (gVal - MEAN[1]) / STD[1];
                data[0][2][y][x] = (b - MEAN[2]) / STD[2];
            }
        }
        return data;
    }
}
