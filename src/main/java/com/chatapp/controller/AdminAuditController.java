package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.AuditLogDto;
import com.chatapp.entity.AuditLog;
import com.chatapp.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) Long chatRoomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<AuditLog> logs = auditLogService.list(chatRoomId, pageable);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "auditLogs", logs.getContent().stream().map(AuditLogDto::fromEntity).toList(),
                "currentPage", logs.getNumber(),
                "totalPages", logs.getTotalPages(),
                "totalElements", logs.getTotalElements(),
                "hasNext", logs.hasNext(),
                "hasPrevious", logs.hasPrevious()
        )));
    }
}
