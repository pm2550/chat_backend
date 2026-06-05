package com.chatapp.admin;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.repository.WorkspaceFileRepository;
import com.chatapp.repository.WorkspaceFileVersionRepository;
import com.chatapp.security.FileVaultService;
import com.chatapp.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EncryptLegacyUploadsRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EncryptLegacyUploadsRunner.class);

    private final FileStorageConfig config;
    private final FileVaultService fileVaultService;
    private final FileStorageService fileStorageService;
    private final WorkspaceFileRepository workspaceFileRepository;
    private final WorkspaceFileVersionRepository workspaceFileVersionRepository;

    public EncryptLegacyUploadsRunner(
            FileStorageConfig config,
            FileVaultService fileVaultService,
            FileStorageService fileStorageService,
            WorkspaceFileRepository workspaceFileRepository,
            WorkspaceFileVersionRepository workspaceFileVersionRepository) {
        this.config = config;
        this.fileVaultService = fileVaultService;
        this.fileStorageService = fileStorageService;
        this.workspaceFileRepository = workspaceFileRepository;
        this.workspaceFileVersionRepository = workspaceFileVersionRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!config.isEncryptLegacyOnStartup()) {
            return;
        }
        Path root = Path.of(config.getFullUploadDir());
        Set<String> excluded = excludedDirs();
        Summary summary = new Summary();
        Map<String, Integer> byDir = new HashMap<>();

        if (!Files.exists(root)) {
            log.warn("ENCRYPT_LEGACY upload root missing: {}", root);
            return;
        }

        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                Path rel = root.relativize(path);
                String fileName = path.getFileName().toString();
                if (isExcluded(rel, excluded)) {
                    summary.excluded++;
                    log.info("ENCRYPT_LEGACY EXCLUDED {}", rel);
                    continue;
                }
                if (fileName.endsWith(".enc") || fileName.endsWith(".meta.json")) {
                    summary.skipped++;
                    continue;
                }
                if (fileVaultService.isEncrypted(path)) {
                    summary.skipped++;
                    log.info("ENCRYPT_LEGACY SKIPPED {} already encrypted", rel);
                    continue;
                }
                try {
                    long size = Files.size(path);
                    if (size <= fileVaultService.getStreamingThreshold()) {
                        fileVaultService.storeEncrypted(path, Files.readAllBytes(path));
                    } else {
                        fileVaultService.storeEncryptedStream(path, Files.newInputStream(path), size);
                    }
                    Files.delete(path);
                    summary.migrated++;
                    byDir.merge(firstSegment(rel), 1, Integer::sum);
                    log.info("ENCRYPT_LEGACY MIGRATED {} size={}", rel, size);
                } catch (Exception e) {
                    summary.errors++;
                    log.error("ENCRYPT_LEGACY ERROR {}", rel, e);
                }
            }
        }
        log.info("ENCRYPT_LEGACY done: by-dir {}; total migrated={} skipped={} errors={} excluded={}",
                byDir, summary.migrated, summary.skipped, summary.errors, summary.excluded);
        migrateWorkspaceObjects(summary, byDir);
        log.info("ENCRYPT_LEGACY workspace objects done: by-dir {}; object migrated={} skipped={} errors={}",
                byDir, summary.objectMigrated, summary.objectSkipped, summary.objectErrors);
    }

    private void migrateWorkspaceObjects(Summary summary, Map<String, Integer> byDir) {
        workspaceFileRepository.findObjectStoredFiles().forEach(file -> migrateWorkspaceObject(
                summary,
                byDir,
                file.getStorageProvider(),
                file.getObjectKey(),
                "workspace_files#" + file.getId()));
        workspaceFileVersionRepository.findObjectStoredVersions().forEach(version -> migrateWorkspaceObject(
                summary,
                byDir,
                version.getStorageProvider(),
                version.getObjectKey(),
                "workspace_file_versions#" + version.getId()));
    }

    private void migrateWorkspaceObject(
            Summary summary,
            Map<String, Integer> byDir,
            String provider,
            String objectKey,
            String source) {
        try {
            boolean migrated = fileStorageService.encryptLegacyWorkspaceObjectIfPlaintext(provider, objectKey);
            if (migrated) {
                summary.objectMigrated++;
                byDir.merge("workspace-object", 1, Integer::sum);
                log.info("ENCRYPT_LEGACY MIGRATED object {} source={}", objectKey, source);
            } else {
                summary.objectSkipped++;
            }
        } catch (Exception e) {
            summary.objectErrors++;
            log.error("ENCRYPT_LEGACY ERROR object {} source={}", objectKey, source, e);
        }
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

    private String firstSegment(Path rel) {
        return rel.getNameCount() == 0 ? "." : rel.getName(0).toString();
    }

    private static class Summary {
        int migrated;
        int skipped;
        int errors;
        int excluded;
        int objectMigrated;
        int objectSkipped;
        int objectErrors;
    }
}
