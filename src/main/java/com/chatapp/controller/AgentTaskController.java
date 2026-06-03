package com.chatapp.controller;

import com.chatapp.dto.AgentTaskDto;
import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.service.AgentWorkflowService;
import com.chatapp.service.AuditLogService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent-tasks")
@RequiredArgsConstructor
@Deprecated(forRemoval = false)
public class AgentTaskController {

    private final AgentWorkflowService agentWorkflowService;
    private final UserService userService;
    private final RawWebSocketHandler rawWebSocketHandler;
    private final AuditLogService auditLogService;

    @PostMapping
    @Deprecated(forRemoval = false)
    public ResponseEntity<ApiResponse<AgentTaskDto>> createTask(
            @RequestBody AgentTaskDto.CreateRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AgentTask task = agentWorkflowService.createAndRun(currentUser.getId(), request);
        if (task.getResultMessage() != null) {
            rawWebSocketHandler.broadcastMessageExcept(task.getResultMessage(), currentUser.getId());
        }
        auditLogService.record(
                task.getRequestedBy(),
                "AGENT_TASK_RUN",
                "AGENT_TASK",
                task.getId(),
                request.getChatRoomId(),
                task.getStatus().name());
        return ResponseEntity.ok(ApiResponse.success("Agent 任务已执行", AgentTaskDto.fromEntity(task)));
    }

    @GetMapping
    @Deprecated(forRemoval = false)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTasks(
            @RequestParam Long chatRoomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<AgentTask> tasks = agentWorkflowService.listRoomTasks(chatRoomId, currentUser.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "tasks", tasks.getContent().stream().map(AgentTaskDto::fromEntity).toList(),
                "currentPage", tasks.getNumber(),
                "totalPages", tasks.getTotalPages(),
                "totalElements", tasks.getTotalElements(),
                "hasNext", tasks.hasNext(),
                "hasPrevious", tasks.hasPrevious()
        )));
    }

    @GetMapping("/{taskId}")
    @Deprecated(forRemoval = false)
    public ResponseEntity<ApiResponse<AgentTaskDto>> getTask(
            @PathVariable Long taskId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AgentTask task = agentWorkflowService.getTask(taskId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(AgentTaskDto.fromEntity(task)));
    }
}
