package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.exception.ClientTooOldException;
import com.chatapp.exception.PasswordUpgradeRequiredException;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
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

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserDto.JwtResponse>> login(@Valid @RequestBody UserDto.LoginRequest request) {
        try {
            User authenticated = userService.authenticate(request);
            String accessToken = jwtUtils.generateAccessToken(authenticated.getUsername());
            String refreshToken = jwtUtils.generateRefreshToken(authenticated.getUsername());

            userService.updateOnlineStatus(authenticated.getId(), User.OnlineStatus.ONLINE);
            UserDto user = userService.findByUsername(authenticated.getUsername());

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
    public ResponseEntity<ApiResponse<UserDto>> register(@Valid @RequestBody UserDto.RegisterRequest request) {
        try {
            UserDto user = userService.registerUser(request);
            return ResponseEntity.ok(ApiResponse.success("注册成功", user));
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
            userService.updateOnlineStatus(user.getId(), User.OnlineStatus.OFFLINE);

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
            UserDto user = userService.findByUsername(username);

            UserDto.JwtResponse jwtResponse = new UserDto.JwtResponse(newAccessToken, jwt, user);
            return ResponseEntity.ok(ApiResponse.success("令牌刷新成功", jwtResponse));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌刷新失败"));
        }
    }

    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<UserDto>> validateToken(@RequestHeader("Authorization") String token) {
        try {
            String jwt = token.substring(7);
            if (jwtUtils.validateJwtToken(jwt)) {
                String tokenId = jwtUtils.getTokenId(jwt);
                if (tokenBlacklistService.isBlacklisted(tokenId)) {
                    return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌已失效"));
                }
                String username = jwtUtils.getUserNameFromJwtToken(jwt);
                UserDto user = userService.findByUsername(username);
                return ResponseEntity.ok(ApiResponse.success("令牌有效", user));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("无效的令牌"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("令牌验证失败"));
        }
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
