package com.chatapp.dto;

import com.chatapp.entity.AgentTask;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentTaskDto {
    private Long id;
    private Long chatRoomId;
    private Long requestedById;
    private Long botId;
    private String prompt;
    private String result;
    private String errorMessage;
    private Long artifactWorkspaceId;
    private Long artifactFolderId;
    private Long artifactFileId;
    private String artifactFileName;
    private AgentTask.Status status;
    private MessageDto resultMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public static AgentTaskDto fromEntity(AgentTask task) {
        AgentTaskDto dto = new AgentTaskDto();
        dto.setId(task.getId());
        dto.setChatRoomId(task.getChatRoom() != null ? task.getChatRoom().getId() : null);
        dto.setRequestedById(task.getRequestedBy() != null ? task.getRequestedBy().getId() : null);
        dto.setBotId(task.getBotConfig() != null ? task.getBotConfig().getId() : null);
        dto.setPrompt(task.getPrompt());
        dto.setResult(task.getResult());
        dto.setErrorMessage(task.getErrorMessage());
        dto.setArtifactWorkspaceId(task.getArtifactWorkspaceId());
        dto.setArtifactFolderId(task.getArtifactFolderId());
        dto.setArtifactFileId(task.getArtifactFileId());
        dto.setArtifactFileName(task.getArtifactFileName());
        dto.setStatus(task.getStatus());
        dto.setResultMessage(MessageDto.fromEntity(task.getResultMessage()));
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setCompletedAt(task.getCompletedAt());
        return dto;
    }

    @Data
    public static class CreateRequest {
        private Long chatRoomId;
        private Long botId;
        private String prompt;
        private Long artifactWorkspaceId;
        private Long artifactFolderId;
        private String artifactFileName;
    }

}
