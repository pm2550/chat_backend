package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.SelfUserDto;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.exception.ClientTooOldException;
import com.chatapp.exception.PasswordUpgradeRequiredException;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserPresenceService;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
import com.chatapp.websocket.RawWebSocketHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserPresenceService userPresenceService;
    private final RawWebSocketHandler rawWebSocketHandler;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserDto.JwtResponse>> login(@Valid @RequestBody UserDto.LoginRequest request) {
        try {
            User authenticated = userService.authenticate(request);
            String accessToken = jwtUtils.generateAccessToken(authenticated.getUsername());
            String refreshToken = jwtUtils.generateRefreshToken(authenticated.getUsername());

            // 登录不改在线状态：只登录、没打开实时连接的人不算在线（连接建立时才上线）。
            SelfUserDto user = selfView(userService.findSelfByUsername(authenticated.getUsername()));

            UserDto.JwtResponse jwtResponse = new UserDto.JwtResponse(accessToken, refreshToken, user);
            return ResponseEntity.ok(ApiResponse.success("登录成功", jwtResponse));
        } catch (PasswordUpgradeRequiredException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "PASSWORD_UPGRADE_REQUIRED"));
        } catch (ClientTooOldException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "CLIENT_TOO_OLD"));
        } catch (LockedException e) {
            return ResponseEntity.status(423).body(ApiResponse.error(423, e.getMessage()));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.unauthorized("用户名或密码错误"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("用户名或密码错误"));
        }
    }

    @GetMapping("/client-salt-params")
    public ResponseEntity<ApiResponse<UserDto.ClientSaltParamsResponse>> clientSaltParams(
            @RequestParam @NotBlank String username) {
        UserDto.ClientSaltParamsResponse params = userService.resolveClientSaltParams(username);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(ApiResponse.success(params));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<SelfUserDto>> register(@Valid @RequestBody UserDto.RegisterRequest request) {
        try {
            UserDto user = userService.registerUser(request);
            // 回包给注册者本人：邮箱、手机号就是他刚提交的那两项。
            return ResponseEntity.ok(ApiResponse.success("注册成功",
                    new SelfUserDto(user, request.getEmail(), request.getPhone())));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader("Authorization") String token) {
        try {
            String jwt = token.substring(7);
            String tokenId = jwtUtils.getTokenId(jwt);
            long remainingMs = jwtUtils.getRemainingExpirationMs(jwt);
            tokenBlacklistService.blacklistToken(tokenId, remainingMs);

            String username = jwtUtils.getUserNameFromJwtToken(jwt);
            UserDto user = userService.findByUsername(username);
            // 退出的这台设备随后会断开连接；别的设备还连着就仍然在线，否则现在就是离线。
            rawWebSocketHandler.syncPresence(user.getId());

            SecurityContextHolder.clearContext();
            return ResponseEntity.ok(ApiResponse.<Void>success("登出成功", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.<Void>success("登出成功", null));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<UserDto.JwtResponse>> refreshToken(@RequestHeader("Authorization") String token) {
        try {
            String jwt = token.substring(7);

            if (!jwtUtils.validateJwtToken(jwt)) {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("无效的令牌"));
            }

            String tokenId = jwtUtils.getTokenId(jwt);
            if (tokenBlacklistService.isBlacklisted(tokenId)) {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌已失效"));
            }

            String tokenType = jwtUtils.getTokenType(jwt);
            if (!"refresh".equals(tokenType)) {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("请提供刷新令牌"));
            }

            String username = jwtUtils.getUserNameFromJwtToken(jwt);
            String newAccessToken = jwtUtils.generateAccessToken(username);
            // 每次续期都换一张新的 refresh token（滑动窗口）：只要在有效期内打开过，
            // 就不会因为距离上次输密码满了 N 天而被强制重新登录。
            // 旧 token 不拉黑——客户端可能并发发起多次续期，拉黑会误杀后到的那次。
            String newRefreshToken = jwtUtils.generateRefreshToken(username);
            SelfUserDto user = selfView(userService.findSelfByUsername(username));

            UserDto.JwtResponse jwtResponse = new UserDto.JwtResponse(newAccessToken, newRefreshToken, user);
            return ResponseEntity.ok(ApiResponse.success("令牌刷新成功", jwtResponse));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌刷新失败"));
        }
    }

    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<SelfUserDto>> validateToken(@RequestHeader("Authorization") String token) {
        try {
            String jwt = token.substring(7);
            if (jwtUtils.validateJwtToken(jwt)) {
                String tokenId = jwtUtils.getTokenId(jwt);
                if (tokenBlacklistService.isBlacklisted(tokenId)) {
                    return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌已失效"));
                }
                String username = jwtUtils.getUserNameFromJwtToken(jwt);
                SelfUserDto user = selfView(userService.findSelfByUsername(username));
                return ResponseEntity.ok(ApiResponse.success("令牌有效", user));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("无效的令牌"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌验证失败"));
        }
    }

    /**
     * 返回给本人的资料里，状态是他自己选的（在线/离开/忙碌/隐身）。库里的 online_status
     * 表示"现在有没有连着"，登录、续期时连接往往还没建立，不能当成"我的状态"。
     */
    private SelfUserDto selfView(SelfUserDto user) {
        if (user != null && user.getProfile() != null && user.getId() != null) {
            user.getProfile().setOnlineStatus(userPresenceService.chosenPresence(user.getId()));
        }
        return user;
    }

    @GetMapping("/check-username")
    public ResponseEntity<ApiResponse<Boolean>> checkUsername(@RequestParam String username) {
        try {
            userService.findByUsername(username);
            return ResponseEntity.ok(ApiResponse.success(false));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(true));
        }
    }
}
