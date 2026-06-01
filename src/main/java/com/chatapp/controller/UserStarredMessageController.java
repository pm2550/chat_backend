package com.chatapp.controller;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.service.MessageReactionService;
import com.chatapp.service.MessageService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserStarredMessageController {
    private final MessageService messageService;
    private final MessageReactionService messageReactionService;
    private final UserService userService;

    @GetMapping("/starred")
    public ResponseEntity<?> getStarredMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        User currentUser = userService.findUserByUsername(auth.getName());
        Page<Message> messages = messageService.getStarredMessages(
                currentUser.getId(),
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(Map.of(
                "messages", messageReactionService.attachAggregates(
                        messages.getContent().stream().map(MessageDto::fromEntity).toList(),
                        currentUser.getId()),
                "currentPage", messages.getNumber(),
                "totalPages", messages.getTotalPages(),
                "totalElements", messages.getTotalElements()
        ));
    }
}
