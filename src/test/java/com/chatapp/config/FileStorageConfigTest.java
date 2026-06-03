package com.chatapp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileStorageConfigTest {

    @Test
    void absoluteUploadDirIsNotPrefixedWithWorkingDirectory() {
        FileStorageConfig config = new FileStorageConfig();
        config.setUploadDir("/var/lib/chat/uploads");

        assertThat(config.getFullUploadDir()).isEqualTo("/var/lib/chat/uploads");
        assertThat(config.getFullChatFileDir()).isEqualTo("/var/lib/chat/uploads/chat-files");
    }

    @Test
    void relativeUploadDirIsResolvedAgainstWorkingDirectory() {
        FileStorageConfig config = new FileStorageConfig();
        config.setUploadDir("target/test-upload-root");

        assertThat(config.getFullUploadDir()).endsWith("target/test-upload-root");
        assertThat(config.getFullAvatarDir()).endsWith("target/test-upload-root/avatars");
    }
}
