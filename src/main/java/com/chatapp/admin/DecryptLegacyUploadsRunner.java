package com.chatapp.admin;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.security.FileVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DecryptLegacyUploadsRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DecryptLegacyUploadsRunner.class);

    private final FileStorageConfig config;
    private final FileVaultService fileVaultService;

    public DecryptLegacyUploadsRunner(FileStorageConfig config, FileVaultService fileVaultService) {
        this.config = config;
        this.fileVaultService = fileVaultService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!config.isDecryptLegacyOnStartup()) {
            return;
        }
        Path root = Path.of(config.getFullUploadDir());
        Set<String> excluded = excludedDirs();
        int decrypted = 0;
        int skipped = 0;
        int errors = 0;
        int excludedCount = 0;

        if (!Files.exists(root)) {
            log.warn("DECRYPT_LEGACY upload root missing: {}", root);
            return;
        }

        try (var stream = Files.walk(root)) {
            for (Path encPath : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".enc"))
                    .toList()) {
                Path rel = root.relativize(encPath);
                if (isExcluded(rel, excluded)) {
                    excludedCount++;
                    log.info("DECRYPT_LEGACY EXCLUDED {}", rel);
                    continue;
                }
                Path basePath = encPath.resolveSibling(stripEnc(encPath.getFileName().toString()));
                if (!fileVaultService.isEncrypted(basePath)) {
                    skipped++;
                    continue;
                }
                try {
                    Files.write(basePath, fileVaultService.loadDecrypted(basePath));
                    fileVaultService.deleteEncrypted(basePath);
                    decrypted++;
                    log.info("DECRYPT_LEGACY DECRYPTED {}", root.relativize(basePath));
                } catch (Exception e) {
                    errors++;
                    log.error("DECRYPT_LEGACY ERROR {}", rel, e);
                }
            }
        }
        log.info("DECRYPT_LEGACY done: decrypted={} skipped={} errors={} excluded={}",
                decrypted, skipped, errors, excludedCount);
    }

    private Set<String> excludedDirs() {
        String raw = config.getLegacyEncryptExcludeDirs();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private boolean isExcluded(Path rel, Set<String> excluded) {
        for (Path segment : rel) {
            if (excluded.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String stripEnc(String fileName) {
        return fileName.substring(0, fileName.length() - ".enc".length());
    }
}
