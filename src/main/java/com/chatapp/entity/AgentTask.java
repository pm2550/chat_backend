package com.chatapp.entity;

import com.chatapp.dto.BotDto;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工作流任务。
 */
@Entity
@Table(name = "agent_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bot_config_id")
    private BotConfig botConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_message_id")
    private Message resultMessage;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String prompt;

    @Transient
    private List<BotDto.ImageAttachment> imageAttachments = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "artifact_workspace_id")
    private Long artifactWorkspaceId;

    @Column(name = "artifact_folder_id")
    private Long artifactFolderId;

    @Column(name = "artifact_file_id")
    private Long artifactFileId;

    @Column(name = "artifact_file_name", length = 255)
    private String artifactFileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
