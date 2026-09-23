package com.chatapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoiceTranscoderTest {

    @TempDir
    Path tmp;

    private final VoiceTranscoder transcoder = new VoiceTranscoder("ffmpeg");

    /** 用 ffmpeg 现场合成一段 2 秒的语音，格式与真实客户端录出来的一致。 */
    private byte[] synthesize(String fileName, List<String> codecArgs) throws Exception {
        Path out = tmp.resolve(fileName);
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2"));
        cmd.addAll(codecArgs);
        cmd.add(out.toString());
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            assumeTrue(false, "本机没有 ffmpeg，跳过");
            return new byte[0];
        }
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0,
                "本机 ffmpeg 缺少对应编码器，跳过");
        return Files.readAllBytes(out);
    }

    private static boolean looksLikeMp3(byte[] bytes) {
        if (bytes.length < 3) return false;
        boolean id3 = bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3';
        boolean frameSync = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0;
        return id3 || frameSync;
    }

    @Test
    void convertsChromeWebmOpusRecordingToMp3() throws Exception {
        byte[] webm = synthesize("voice.webm", List.of("-c:a", "libopus", "-f", "webm"));

        Optional<byte[]> mp3 = transcoder.toMp3(webm);

        assertTrue(mp3.isPresent());
        assertTrue(looksLikeMp3(mp3.get()));
    }

    @Test
    void convertsIphoneMp4AacRecordingToMp3() throws Exception {
        byte[] m4a = synthesize("voice.m4a", List.of("-c:a", "aac", "-f", "mp4"));

        Optional<byte[]> mp3 = transcoder.toMp3(m4a);

        assertTrue(mp3.isPresent());
        assertTrue(looksLikeMp3(mp3.get()));
    }

    @Test
    void garbageInputFallsBackToOriginalInsteadOfFailing() {
        assertFalse(transcoder.toMp3(new byte[]{1, 2, 3, 4, 5}).isPresent());
        assertFalse(transcoder.toMp3(new byte[0]).isPresent());
    }

    @Test
    void missingFfmpegFallsBackToOriginalInsteadOfFailing() {
        VoiceTranscoder broken = new VoiceTranscoder("/nonexistent/ffmpeg");

        assertFalse(broken.toMp3(new byte[]{1, 2, 3}).isPresent());
    }
}
