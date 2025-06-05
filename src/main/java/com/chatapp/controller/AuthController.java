package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtils jwtUtils;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserDto.JwtResponse>> login(@Validated @RequestBody UserDto.LoginRequest request) {
        try {
            // 验证用户凭据
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 生成JWT令牌
            String jwt = jwtUtils.generateJwtToken(request.getUsername());

            // 获取用户信息
            UserDto user = userService.findByUsername(request.getUsername());

            // 更新用户在线状态
            userService.updateOnlineStatus(user.getId(), com.chatapp.entity.User.OnlineStatus.ONLINE);

            UserDto.JwtResponse jwtResponse = new UserDto.JwtResponse(jwt, user);

            return ResponseEntity.ok(ApiResponse.success("登录成功", jwtResponse));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("用户名或密码错误"));
        }
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserDto>> register(@Validated @RequestBody UserDto.RegisterRequest request) {
        try {
            UserDto user = userService.registerUser(request);
            return ResponseEntity.ok(ApiResponse.success("注册成功", user));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("注册失败，请稍后重试"));
        }
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader("Authorization") String token) {
        try {
            // 从token中获取用户名
            String jwt = token.substring(7); // 移除 "Bearer " 前缀
            String username = jwtUtils.getUserNameFromJwtToken(jwt);
            
            // 获取用户信息并更新在线状态
            UserDto user = userService.findByUsername(username);
            userService.updateOnlineStatus(user.getId(), com.chatapp.entity.User.OnlineStatus.OFFLINE);

            // 清除安全上下文
            SecurityContextHolder.clearContext();

            return ResponseEntity.ok(ApiResponse.success("登出成功"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success("登出成功"));
        }
    }

    /**
     * 刷新令牌
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<UserDto.JwtResponse>> refreshToken(@RequestHeader("Authorization") String token) {
        try {
            String jwt = token.substring(7); // 移除 "Bearer " 前缀
            
            if (jwtUtils.validateJwtToken(jwt)) {
                String newToken = jwtUtils.refreshToken(jwt);
                String username = jwtUtils.getUserNameFromJwtToken(newToken);
                UserDto user = userService.findByUsername(username);
                
                UserDto.JwtResponse jwtResponse = new UserDto.JwtResponse(newToken, user);
                return ResponseEntity.ok(ApiResponse.success("令牌刷新成功", jwtResponse));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.badRequest("无效的令牌"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("令牌刷新失败"));
        }
    }

    /**
     * 验证令牌
     */
    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<UserDto>> validateToken(@RequestHeader("Authorization") String token) {
        try {
            String jwt = token.substring(7); // 移除 "Bearer " 前缀
            
            if (jwtUtils.validateJwtToken(jwt)) {
                String username = jwtUtils.getUserNameFromJwtToken(jwt);
                UserDto user = userService.findByUsername(username);
                return ResponseEntity.ok(ApiResponse.success("令牌有效", user));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.badRequest("无效的令牌"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("令牌验证失败"));
        }
    }

    /**
     * 检查用户名是否可用
     */
    @GetMapping("/check-username")
    public ResponseEntity<ApiResponse<Boolean>> checkUsername(@RequestParam String username) {
        try {
            userService.findByUsername(username);
            // 如果找到用户，用户名不可用
            return ResponseEntity.ok(ApiResponse.success(false));
        } catch (Exception e) {
            // 用户不存在，用户名可用
            return ResponseEntity.ok(ApiResponse.success(true));
        }
    }
} 