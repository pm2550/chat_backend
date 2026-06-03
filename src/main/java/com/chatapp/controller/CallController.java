package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.entity.User;
import com.chatapp.service.TurnCredentialService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallController {

    private final TurnCredentialService turnCredentialService;
    private final UserService userService;

    @GetMapping("/ice-servers")
    public ResponseEntity<ApiResponse<TurnCredentialService.IceServerConfig>> getIceServers(Authentication auth) {
        User currentUser = userService.findUserByUsername(auth.getName());
        return ResponseEntity.ok(ApiResponse.success(turnCredentialService.generate(currentUser.getId())));
    }
}
