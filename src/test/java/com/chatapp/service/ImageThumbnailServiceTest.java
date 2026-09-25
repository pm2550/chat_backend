package com.chatapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class ImageThumbnailServiceTest {

    @TempDir
    Path tmp;

    private final ImageThumbnailService service =
            new ImageThumbnailService(mock(FileStorageService.class), "ffmpeg");

    @Test
    void largePhotoGetsSmallJpegThumbnailWithinBounds() throws Exception {
        byte[] photo = noisyJpeg(3000, 2000, 0.95f);
        assertTrue(photo.length > 1_000_000, "测试图要像真实照片那样大: " + photo.length);

        ImageThumbnailService.Thumbnail thumbnail = service.generate(photo).orElseThrow();

        assertEquals("image/jpeg", thumbnail.contentType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
        assertEquals(400, decoded.getWidth());
        assertEquals(267, decoded.getHeight());
        assertTrue(thumbnail.bytes().length < 80 * 1024, "缩略图应在几十 KB: " + thumbnail.bytes().length);
        assertEquals(3000, thumbnail.sourceWidth());
        assertEquals(2000, thumbnail.sourceHeight());
    }

    @Test
    void exifOrientationIsAppliedToThumbnailAndReportedSize() throws Exception {
        // 原始像素横着存：左半红、右半蓝；EXIF 方向 6 = 显示时顺时针转 90°，左半边应该到上面。
        BufferedImage raw = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = raw.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 800, 800);
        g.setColor(Color.BLUE);
        g.fillRect(800, 0, 800, 800);
        g.dispose();
        addNoise(raw, 12);
        byte[] jpeg = withExifOrientation(encodeJpeg(raw, 0.95f), 6);
        assertEquals(6, ImageThumbnailService.readJpegOrientation(jpeg));

        ImageThumbnailService.Thumbnail thumbnail = service.generate(jpeg).orElseThrow();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
        assertEquals(200, decoded.getWidth());
        assertEquals(400, decoded.getHeight());
        assertTrue(isReddish(decoded.getRGB(100, 40)), "上方应是原图左半边（红）");
        assertTrue(isBluish(decoded.getRGB(100, 360)), "下方应是原图右半边（蓝）");
        assertEquals(800, thumbnail.sourceWidth());
        assertEquals(1600, thumbnail.sourceHeight());
    }

    @Test
    void smallAnimatedOrBrokenImagesGetNoThumbnail() throws Exception {
        // 原图本来就小：客户端直接加载原图。
        assertTrue(service.generate(encodeJpeg(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), 0.8f))
                .isEmpty());
        // GIF 按动图对待：静态缩略图会让它不动。
        byte[] gif = new byte[200 * 1024];
        System.arraycopy("GIF89a".getBytes(), 0, gif, 0, 6);
        assertTrue(service.generate(gif).isEmpty());
        // APNG（IDAT 之前有 acTL）同理。
        assertTrue(service.generate(fakeApng()).isEmpty());
        // 损坏的数据不抛异常。
        byte[] broken = new byte[300 * 1024];
        broken[0] = (byte) 0xFF;
        broken[1] = (byte) 0xD8;
        broken[2] = (byte) 0xFF;
        assertTrue(service.generate(broken).isEmpty());
    }

    @Test
    void transparentPngKeepsTransparencyAsPngThumbnail() throws Exception {
        BufferedImage image = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(7);
        for (int y = 0; y < 1200; y++) {
            for (int x = 0; x < 1200; x++) {
                boolean inside = (x - 600) * (x - 600) + (y - 600) * (y - 600) < 500 * 500;
                image.setRGB(x, y, inside ? (0xFF << 24) | random.nextInt(0xFFFFFF) : 0);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        ImageThumbnailService.Thumbnail thumbnail = service.generate(png.toByteArray()).orElseThrow();

        assertEquals("image/png", thumbnail.contentType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
        assertEquals(400, decoded.getWidth());
        assertEquals(0, decoded.getRGB(2, 2) >>> 24, "角上仍是透明的");
    }

    @Test
    void staticWebpIsDecodedThroughFfmpeg() throws Exception {
        Path source = tmp.resolve("photo.png");
        ImageIO.write(noisy(1600, 1200), "png", source.toFile());
        Path webp = tmp.resolve("photo.webp");
        Process process;
        try {
            process = new ProcessBuilder(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", source.toString(), "-c:v", "libwebp", "-quality", "95", webp.toString()))
                    .redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            assumeTrue(false, "本机没有 ffmpeg，跳过");
            return;
        }
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "ffmpeg 没有 libwebp，跳过");
        byte[] bytes = Files.readAllBytes(webp);
        assumeTrue(bytes.length > ImageThumbnailService.SMALL_ORIGINAL_BYTES);

        Optional<ImageThumbnailService.Thumbnail> thumbnail = service.generate(bytes);

        assertTrue(thumbnail.isPresent());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.get().bytes()));
        assertEquals(400, decoded.getWidth());
        assertEquals(300, decoded.getHeight());
    }

    // ---------------------------------------------------------------- helpers

    static byte[] noisyJpeg(int width, int height, float quality) throws Exception {
        return encodeJpeg(noisy(width, height), quality);
    }

    static BufferedImage noisy(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (x * 255 / width);
                int r = clamp(base + random.nextInt(60) - 30);
                int gr = clamp(128 + random.nextInt(60) - 30);
                int b = clamp(255 - base + random.nextInt(60) - 30);
                image.setRGB(x, y, (r << 16) | (gr << 8) | b);
            }
        }
        return image;
    }

    private static void addNoise(BufferedImage image, int amplitude) {
        Random random = new Random(3);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = clamp(((rgb >> 16) & 0xFF) + random.nextInt(amplitude * 2) - amplitude);
                int g = clamp(((rgb >> 8) & 0xFF) + random.nextInt(amplitude * 2) - amplitude);
                int b = clamp((rgb & 0xFF) + random.nextInt(amplitude * 2) - amplitude);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    static byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    /** 在 SOI 后插一个只含 Orientation 的 EXIF APP1（大端 TIFF）。 */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
        return result;
    }

    private static byte[] fakeApng() {
        byte[] bytes = new byte[200 * 1024];
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 1, 0, 0, 0, 1, 0, 8, 6, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 8, 'a', 'c', 'T', 'L'};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static boolean isReddish(int rgb) {
        return ((rgb >> 16) & 0xFF) > 180 && (rgb & 0xFF) < 90;
    }

    private static boolean isBluish(int rgb) {
        return (rgb & 0xFF) > 180 && ((rgb >> 16) & 0xFF) < 90;
    }
}
