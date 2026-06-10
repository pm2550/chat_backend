package com.chatapp.controller;

import com.chatapp.dto.ContactGroupDto;
import com.chatapp.entity.User;
import com.chatapp.service.ContactGroupService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/contact-groups")
@RequiredArgsConstructor
@Slf4j
public class ContactGroupController {

    private final ContactGroupService contactGroupService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        try {
            User currentUser = currentUser(auth);
            return ResponseEntity.ok(contactGroupService.listGroups(currentUser.getId()));
        } catch (Exception e) {
            return handle("获取通讯录分组失败", e);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody ContactGroupDto.UpsertGroupRequest request,
            Authentication auth) {
        try {
            User currentUser = currentUser(auth);
            ContactGroupDto.GroupSummary group =
                    contactGroupService.createGroup(currentUser.getId(), request);
            return ResponseEntity.ok(Map.of("message", "分组已创建", "group", group));
        } catch (Exception e) {
            return handle("创建通讯录分组失败", e);
        }
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<?> update(
            @PathVariable Long groupId,
            @Valid @RequestBody ContactGroupDto.UpsertGroupRequest request,
            Authentication auth) {
        try {
            User currentUser = currentUser(auth);
            ContactGroupDto.GroupSummary group =
                    contactGroupService.updateGroup(currentUser.getId(), groupId, request);
            return ResponseEntity.ok(Map.of("message", "分组已更新", "group", group));
        } catch (Exception e) {
            return handle("更新通讯录分组失败", e);
        }
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<?> delete(@PathVariable Long groupId, Authentication auth) {
        try {
            User currentUser = currentUser(auth);
            contactGroupService.deleteGroup(currentUser.getId(), groupId);
            return ResponseEntity.ok(Map.of("message", "分组已删除"));
        } catch (Exception e) {
            return handle("删除通讯录分组失败", e);
        }
    }

    @PostMapping("/reorder")
    public ResponseEntity<?> reorder(
            @Valid @RequestBody ContactGroupDto.ReorderRequest request,
            Authentication auth) {
        try {
            User currentUser = currentUser(auth);
            return ResponseEntity.ok(Map.of(
                    "message", "分组排序已更新",
                    "groups", contactGroupService.reorderGroups(
                            currentUser.getId(),
                            request.getGroupIds())));
        } catch (Exception e) {
            return handle("更新通讯录分组排序失败", e);
        }
    }

    @PutMapping("/items")
    public ResponseEntity<?> assignItem(
            @Valid @RequestBody ContactGroupDto.AssignItemRequest request,
            Authentication auth) {
        try {
            User currentUser = currentUser(auth);
            ContactGroupDto.ItemAssignment assignment =
                    contactGroupService.assignItem(currentUser.getId(), request);
            Map<String, Object> response = new HashMap<>();
            response.put("message", assignment == null ? "已移出分组" : "已移动到分组");
            response.put("assignment", assignment);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return handle("移动通讯录条目失败", e);
        }
    }

    private User currentUser(Authentication auth) {
        return userService.findUserByUsername(auth.getName());
    }

    private ResponseEntity<?> handle(String action, Exception e) {
        log.error("{}: {}", action, e.getMessage());
        if (e instanceof AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
