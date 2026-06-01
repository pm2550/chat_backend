package com.chatapp.service;

import com.chatapp.entity.AuditLog;
import com.chatapp.entity.User;
import com.chatapp.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User actor,
                       String action,
                       String resourceType,
                       Long resourceId,
                       Long chatRoomId,
                       String detail) {
        try {
            AuditLog auditLog = new AuditLog();
            if (actor != null) {
                auditLog.setActorId(actor.getId());
                auditLog.setActorUsername(actor.getUsername());
            }
            auditLog.setAction(action);
            auditLog.setResourceType(resourceType);
            auditLog.setResourceId(resourceId);
            auditLog.setChatRoomId(chatRoomId);
            auditLog.setDetail(detail);
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.warn("Audit log write failed: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> list(Long chatRoomId, Pageable pageable) {
        if (chatRoomId != null) {
            return auditLogRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId, pageable);
        }
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
