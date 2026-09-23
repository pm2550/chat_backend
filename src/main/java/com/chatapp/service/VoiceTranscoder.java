package com.chatapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 把语音消息统一转成 MP3。
 *
 * 各端录音格式不同（Chrome/Android: webm/opus，iPhone Safari: mp4/aac），
 * 对方设备不一定能播放；MP3 是所有浏览器和手机都能直接播放的格式。
 * 转码失败时返回空，调用方保留原文件照常发送。
 */
@Service
@Slf4j
public class VoiceTranscoder {
    private static final long TIMEOUT_SECONDS = 30;

    private final String ffmpegPath;

    public VoiceTranscoder(@Value("${voice.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public Optional<byte[]> toMp3(byte[] input) {
        if (input == null || input.length == 0) {
            return Optional.empty();
        }
        Path source = null;
        Path target = null;
        try {
            // 用临时文件而不是管道：mp4/m4a 的索引常在文件末尾，从管道读无法回溯解析。
            source = Files.createTempFile("voice-in-", ".bin");
            target = Files.createTempFile("voice-out-", ".mp3");
            Files.write(source, input);
            Process process = new ProcessBuilder(List.of(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", source.toString(),
                    "-vn", "-ac", "1", "-ar", "24000", "-b:a", "48k",
                    "-f", "mp3", target.toString()))
                    .redirectErrorStream(true)
                    .start();
            byte[] ffmpegOutput = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("语音转码超时，保留原文件");
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("语音转码失败，保留原文件: {}", new String(ffmpegOutput).trim());
                return Optional.empty();
            }
            byte[] output = Files.readAllBytes(target);
            return output.length == 0 ? Optional.empty() : Optional.of(output);
        } catch (IOException e) {
            log.warn("语音转码不可用，保留原文件: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteQuietly(source);
            deleteQuietly(target);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
