package com.chatapp.service;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.entity.WorkspaceFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WorkspaceFileScanService {

    private static final String EICAR_SIGNATURE = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";

    private final FileStorageConfig fileStorageConfig;

    public ScanResult scan(MultipartFile file, byte[] bytes) {
        String originalName = file.getOriginalFilename();
        return scan(StringUtils.cleanPath(originalName == null ? "file" : originalName), bytes);
    }

    public ScanResult scan(String originalName, byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        basicGuard(originalName, safeBytes);
        if ("clamav".equalsIgnoreCase(fileStorageConfig.getScanMode())) {
            return clamAvScan(safeBytes);
        }
        return new ScanResult(WorkspaceFile.ScanStatus.CLEAN, "基础安全扫描通过", LocalDateTime.now());
    }

    private void basicGuard(String originalName, byte[] bytes) {
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".exe") || lowerName.endsWith(".bat") || lowerName.endsWith(".cmd")
                || lowerName.endsWith(".sh") || lowerName.endsWith(".js") || lowerName.endsWith(".jar")) {
            throw new IllegalArgumentException("文件安全扫描未通过: 不允许上传可执行脚本或程序");
        }
        String sample = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        if (sample.contains(EICAR_SIGNATURE)) {
            throw new IllegalArgumentException("文件安全扫描未通过: 检测到测试病毒特征");
        }
    }

    private ScanResult clamAvScan(byte[] bytes) {
        try {
            String response = scanWithClamAv(bytes);
            String normalized = response == null ? "" : response.trim();
            if (normalized.contains("FOUND")) {
                throw new IllegalArgumentException("文件安全扫描未通过: " + normalized);
            }
            if (!normalized.contains("OK")) {
                throw new IllegalArgumentException("文件安全扫描返回异常: " + normalized);
            }
            return new ScanResult(WorkspaceFile.ScanStatus.CLEAN, "ClamAV 扫描通过", LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (fileStorageConfig.isScanFailOpen()) {
                return new ScanResult(WorkspaceFile.ScanStatus.FAILED,
                        "扫描服务不可用，按 fail-open 策略放行: " + e.getMessage(),
                        LocalDateTime.now());
            }
            throw new IllegalArgumentException("文件安全扫描服务不可用: " + e.getMessage(), e);
        }
    }

    private String scanWithClamAv(byte[] bytes) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(fileStorageConfig.getClamavHost(), fileStorageConfig.getClamavPort()),
                    fileStorageConfig.getClamavTimeoutMs());
            socket.setSoTimeout(fileStorageConfig.getClamavTimeoutMs());
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                int offset = 0;
                while (offset < bytes.length) {
                    int chunkSize = Math.min(8192, bytes.length - offset);
                    output.writeInt(chunkSize);
                    output.write(bytes, offset, chunkSize);
                    offset += chunkSize;
                }
                output.writeInt(0);
                output.flush();
                return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    public record ScanResult(
            WorkspaceFile.ScanStatus status,
            String summary,
            LocalDateTime scannedAt) {
    }
}
