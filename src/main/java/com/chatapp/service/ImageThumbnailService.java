package com.chatapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 聊天图片的小预览图：长边 400px 的 JPEG（有透明区域的图用 PNG），通常二三十 KB。
 *
 * 聊天气泡、文件中心先加载它，点开再下载原图——慢网下一屏图片从几 MB 变成几十 KB。
 * 缩略图和原图一样存成受保护的聊天文件（/api/files/chat/），访问规则见 FileAccessService。
 *
 * 做不了的情况一律返回空，调用方照常发原图、不带缩略图，客户端退回加载原图：
 * 不认识/损坏的文件、动图（GIF、动态 WebP、APNG——静态缩略图会让它不动了）、
 * 原图本来就很小（另做一张没意义）、超大图（防解压炸弹）。
 */
@Service
@Slf4j
public class ImageThumbnailService {
    public static final int LONG_EDGE = 400;
    /** 原图不超过这么大就不另做缩略图，客户端直接加载原图。回填历史消息也按它筛选。 */
    public static final long SMALL_ORIGINAL_BYTES = 100 * 1024;
    private static final float JPEG_QUALITY = 0.78f;
    /** 缩略图没比原图小多少（超过原图 70%）就不要了。 */
    private static final double MAX_THUMBNAIL_RATIO = 0.7;
    private static final long MAX_SOURCE_PIXELS = 200_000_000L;
    private static final int MAX_SOURCE_BYTES = 60 * 1024 * 1024;
    private static final long FFMPEG_TIMEOUT_SECONDS = 20;

    private final FileStorageService fileStorageService;
    private final String ffmpegPath;

    public ImageThumbnailService(FileStorageService fileStorageService,
                                 @Value("${voice.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.fileStorageService = fileStorageService;
        this.ffmpegPath = ffmpegPath;
    }

    /** 生成好的缩略图；sourceWidth/sourceHeight 是原图按 EXIF 方向摆正后的尺寸。 */
    public record Thumbnail(byte[] bytes, String contentType, int sourceWidth, int sourceHeight) {
        public String extension() {
            return "image/png".equals(contentType) ? "png" : "jpg";
        }
    }

    /** 已存好的缩略图：url 写进 messages.thumbnail_url。 */
    public record StoredThumbnail(String url, int sourceWidth, int sourceHeight) {}

    /** 生成并存盘；做不了（见类注释）或存盘失败都返回空，不影响发消息。 */
    public Optional<StoredThumbnail> createAndStore(byte[] original) {
        Optional<Thumbnail> thumbnail = generate(original);
        if (thumbnail.isEmpty()) {
            return Optional.empty();
        }
        Thumbnail value = thumbnail.get();
        try {
            String url = fileStorageService.uploadChatFileBytes(
                    "thumbnail." + value.extension(), value.contentType(), value.bytes());
            return Optional.of(new StoredThumbnail(url, value.sourceWidth(), value.sourceHeight()));
        } catch (Exception e) {
            log.warn("缩略图保存失败，消息照常发送: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Thumbnail> generate(byte[] original) {
        if (original == null || original.length <= SMALL_ORIGINAL_BYTES || original.length > MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        try {
            SourceKind kind = sniff(original);
            BufferedImage decoded = switch (kind) {
                case JPEG, PNG, BMP -> decodeWithImageIo(original);
                case WEBP -> decodeWithFfmpeg(original);
                case ANIMATED, UNSUPPORTED -> null;
            };
            if (decoded == null) {
                return Optional.empty();
            }
            int orientation = kind == SourceKind.JPEG ? readJpegOrientation(original) : 1;
            boolean transposed = orientation >= 5 && orientation <= 8;
            // 解码时可能按整数倍降采样过：原图尺寸按比例还原不准，所以只在没降采样时才用解码尺寸。
            int[] sourceSize = sourceDimensions(original, kind, decoded);
            int sourceWidth = transposed ? sourceSize[1] : sourceSize[0];
            int sourceHeight = transposed ? sourceSize[0] : sourceSize[1];

            boolean alpha = decoded.getColorModel().hasAlpha();
            int[] target = fitLongEdge(decoded.getWidth(), decoded.getHeight(), LONG_EDGE);
            BufferedImage scaled = downscale(decoded, target[0], target[1], alpha);
            boolean transparent = alpha && hasTransparentPixel(scaled);
            if (alpha && !transparent) {
                scaled = flatten(scaled);
            }
            BufferedImage oriented = applyOrientation(scaled, orientation);
            byte[] bytes = transparent ? encodePng(oriented) : encodeJpeg(oriented);
            if (bytes.length == 0 || bytes.length > original.length * MAX_THUMBNAIL_RATIO) {
                return Optional.empty();
            }
            return Optional.of(new Thumbnail(
                    bytes, transparent ? "image/png" : "image/jpeg", sourceWidth, sourceHeight));
        } catch (Exception | OutOfMemoryError e) {
            log.warn("缩略图生成失败，退回加载原图: {}", e.toString());
            return Optional.empty();
        }
    }

    enum SourceKind { JPEG, PNG, BMP, WEBP, ANIMATED, UNSUPPORTED }

    static SourceKind sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return SourceKind.JPEG;
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return isAnimatedPng(b) ? SourceKind.ANIMATED : SourceKind.PNG;
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return SourceKind.ANIMATED; // GIF 一律按动图对待，客户端直接加载原图
        }
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') {
            return SourceKind.BMP;
        }
        if (b.length >= 30 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            boolean animated = ascii(b, 12, 4).equals("VP8X") && (b[20] & 0x02) != 0;
            return animated ? SourceKind.ANIMATED : SourceKind.WEBP;
        }
        return SourceKind.UNSUPPORTED;
    }

    private static boolean isAnimatedPng(byte[] b) {
        int offset = 8;
        while (offset + 8 <= b.length) {
            long length = readUInt32BE(b, offset);
            String type = ascii(b, offset + 4, 4);
            if ("acTL".equals(type)) {
                return true;
            }
            if ("IDAT".equals(type) || "IEND".equals(type)) {
                return false;
            }
            offset += 12 + (int) Math.min(length, Integer.MAX_VALUE - 12L);
        }
        return false;
    }

    private BufferedImage decodeWithImageIo(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) {
                    return null;
                }
                // 按整数倍降采样解码（解码器边读边丢行列），大图不用先整张解到内存里，
                // 留出 2 倍余量再平滑缩小，缩略图不发虚。
                int subsampling = Math.max(1, Math.max(width, height) / (LONG_EDGE * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** ImageIO 不认 WebP，交给 ffmpeg 解出一张小 PNG 再走同样的流程。 */
    private BufferedImage decodeWithFfmpeg(byte[] bytes) {
        Path source = null;
        Path target = null;
        try {
            source = Files.createTempFile("thumb-in-", ".bin");
            target = Files.createTempFile("thumb-out-", ".png");
            Files.write(source, bytes);
            int bound = LONG_EDGE * 2;
            Process process = new ProcessBuilder(List.of(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", source.toString(),
                    "-frames:v", "1",
                    "-vf", "scale='min(" + bound + ",iw)':'min(" + bound + ",ih)':force_original_aspect_ratio=decrease",
                    target.toString()))
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                log.debug("ffmpeg 解码图片失败: {}", new String(output, StandardCharsets.UTF_8).trim());
                return null;
            }
            return ImageIO.read(target.toFile());
        } catch (IOException e) {
            log.debug("ffmpeg 不可用，不生成缩略图: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            deleteQuietly(source);
            deleteQuietly(target);
        }
    }

    /** 原图（未摆正）的宽高：优先读文件头，读不到就用解码结果。 */
    private static int[] sourceDimensions(byte[] bytes, SourceKind kind, BufferedImage decoded) {
        int[] header = switch (kind) {
            case JPEG -> readJpegSize(bytes);
            case PNG -> bytes.length >= 24
                    ? new int[] {(int) readUInt32BE(bytes, 16), (int) readUInt32BE(bytes, 20)}
                    : null;
            default -> null;
        };
        return header != null ? header : new int[] {decoded.getWidth(), decoded.getHeight()};
    }

    static int[] fitLongEdge(int width, int height, int longEdge) {
        int longest = Math.max(width, height);
        if (longest <= longEdge) {
            return new int[] {width, height};
        }
        double scale = (double) longEdge / longest;
        return new int[] {
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))
        };
    }

    /** 逐级减半的双线性缩小：一步缩到 1/10 会严重锯齿。 */
    private static BufferedImage downscale(BufferedImage source, int targetWidth, int targetHeight, boolean alpha) {
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();
        do {
            width = Math.max(targetWidth, width / 2);
            height = Math.max(targetHeight, height / 2);
            BufferedImage next = new BufferedImage(width, height,
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = next.createGraphics();
            try {
                if (!alpha) {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, width, height);
                }
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(current, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            current = next;
        } while (width != targetWidth || height != targetHeight);
        return current;
    }

    private static boolean hasTransparentPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage flatten(BufferedImage image) {
        BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = opaque.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return opaque;
    }

    /** 按 EXIF 方向（1–8）把图摆正：手机竖拍的照片像素是横着存的。 */
    static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean transposed = orientation >= 5;
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8
        };
        BufferedImage result = new BufferedImage(transposed ? h : w, transposed ? w : h, image.getType());
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(image, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return new byte[0];
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    /** JPEG 里 EXIF（APP1）第 0 个 IFD 的 Orientation 标签；没有或读不出按 1（不用转）。 */
    static int readJpegOrientation(byte[] b) {
        int offset = 2;
        while (offset + 4 <= b.length && (b[offset] & 0xFF) == 0xFF) {
            int marker = b[offset + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = readUInt16BE(b, offset + 2);
            if (length < 2 || offset + 2 + length > b.length) {
                break;
            }
            if (marker == 0xE1 && length >= 16 && ascii(b, offset + 4, 6).equals("Exif\0\0")) {
                return orientationFromTiff(b, offset + 10, offset + 2 + length);
            }
            offset += 2 + length;
        }
        return 1;
    }

    private static int orientationFromTiff(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
        boolean big = b[tiff] == 'M' && b[tiff + 1] == 'M';
        if (!little && !big) {
            return 1;
        }
        long ifd = little ? readUInt32LE(b, tiff + 4) : readUInt32BE(b, tiff + 4);
        int dir = tiff + (int) Math.min(ifd, Integer.MAX_VALUE / 2);
        if (dir + 2 > end) {
            return 1;
        }
        int count = little ? readUInt16LE(b, dir) : readUInt16BE(b, dir);
        for (int i = 0; i < count; i++) {
            int entry = dir + 2 + i * 12;
            if (entry + 12 > end) {
                break;
            }
            int tag = little ? readUInt16LE(b, entry) : readUInt16BE(b, entry);
            if (tag == 0x0112) {
                int value = little ? readUInt16LE(b, entry + 8) : readUInt16BE(b, entry + 8);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int[] readJpegSize(byte[] b) {
        int offset = 2;
        while (offset + 4 <= b.length && (b[offset] & 0xFF) == 0xFF) {
            int marker = b[offset + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return null;
            }
            int length = readUInt16BE(b, offset + 2);
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame && offset + 9 <= b.length) {
                return new int[] {readUInt16BE(b, offset + 7), readUInt16BE(b, offset + 5)};
            }
            if (length < 2) {
                return null;
            }
            offset += 2 + length;
        }
        return null;
    }

    private static String ascii(byte[] b, int offset, int length) {
        if (offset < 0 || offset + length > b.length) {
            return "";
        }
        return new String(b, offset, length, StandardCharsets.ISO_8859_1);
    }

    private static int readUInt16BE(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    private static int readUInt16LE(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private static long readUInt32BE(byte[] b, int offset) {
        return ((long) (b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static long readUInt32LE(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16) | ((long) (b[offset + 3] & 0xFF) << 24);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
