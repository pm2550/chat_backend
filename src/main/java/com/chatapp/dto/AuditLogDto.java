package com.chatapp.dto;

import com.chatapp.entity.AuditLog;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogDto {
    private Long id;
    private Long actorId;
    private String actorUsername;
    private String action;
    private String resourceType;
    private Long resourceId;
    private Long chatRoomId;
    private String detail;
    private LocalDateTime createdAt;

    public static AuditLogDto fromEntity(AuditLog log) {
        AuditLogDto dto = new AuditLogDto();
        dto.setId(log.getId());
        dto.setActorId(log.getActorId());
        dto.setActorUsername(log.getActorUsername());
        dto.setAction(log.getAction());
        dto.setResourceType(log.getResourceType());
        dto.setResourceId(log.getResourceId());
        dto.setChatRoomId(log.getChatRoomId());
        dto.setDetail(log.getDetail());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }
}
