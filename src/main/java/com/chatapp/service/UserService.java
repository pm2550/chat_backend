package com.chatapp.service;

import com.chatapp.config.RateLimitConfig;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.exception.ClientTooOldException;
import com.chatapp.exception.PasswordUpgradeRequiredException;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户服务类
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    public static final String SCHEME_LEGACY = "BCRYPT_LEGACY";
    public static final String SCHEME_CLIENT = "CLIENT_ARGON2_BCRYPT";
    public static final String DEFAULT_ARGON2_PARAMS = "m=65536,t=3,p=1,v=19,hashLen=32";

    private static final Pattern ARGON2_PARAMS_PATTERN = Pattern.compile(
            "^m=(\\d+),t=(\\d+),p=(\\d+),v=(\\d+),hashLen=(\\d+)$");
    private static final Set<String> TITLE_EFFECTS = Set.of(
            "none", "gradient", "glow", "rainbow", "animated_pulse");

    private final UserRepository userRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final ModelMapper modelMapper;
    private final RateLimitConfig rateLimitConfig;

    @Value("${auth.client-salt-hmac-secret}")
    private String clientSaltHmacSecret;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toList());

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(!user.getIsActive())
                .credentialsExpired(false)
                .disabled(!user.getIsActive())
                .build();
    }

    @Transactional(readOnly = true)
    public User authenticate(UserDto.LoginRequest request) {
        String username = request.getUsername();
        if (rateLimitConfig.isLoginLocked(username)) {
            throw new LockedException("登录失败次数过多，请稍后再试");
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            rateLimitConfig.recordLoginFailure(username);
            throw new BadCredentialsException("用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new LockedException("账号已禁用");
        }

        String scheme = normalizeScheme(user.getPasswordScheme());
        String credential = credentialForScheme(scheme, request);
        if (!passwordEncoder.matches(credential, user.getPassword())) {
            rateLimitConfig.recordLoginFailure(username);
            throw new BadCredentialsException("用户名或密码错误");
        }

        rateLimitConfig.resetLoginFailures(username);
        return user;
    }

    public UserDto.ClientSaltParamsResponse resolveClientSaltParams(String username) {
        String fakeSalt = deriveFakeSalt(username);
        User user = userRepository.findByUsername(username).orElse(null);
        if (user != null && SCHEME_CLIENT.equals(normalizeScheme(user.getPasswordScheme()))
                && isNotBlank(user.getClientSalt()) && isNotBlank(user.getArgon2Params())) {
            return new UserDto.ClientSaltParamsResponse(user.getClientSalt(), user.getArgon2Params(), SCHEME_CLIENT);
        }
        return new UserDto.ClientSaltParamsResponse(fakeSalt, DEFAULT_ARGON2_PARAMS, SCHEME_LEGACY);
    }

    /**
     * 用户注册
     */
    @Transactional
    public UserDto registerUser(UserDto.RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("邮箱已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        if (isNotBlank(request.getClientHash())) {
            validateClientCredentialBundle(request.getClientHash(), request.getClientSalt(), request.getArgon2Params());
            user.setPassword(passwordEncoder.encode(request.getClientHash()));
            user.setClientSalt(request.getClientSalt());
            user.setArgon2Params(request.getArgon2Params());
            user.setPasswordScheme(SCHEME_CLIENT);
        } else {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setClientSalt(null);
            user.setArgon2Params(null);
            user.setPasswordScheme(SCHEME_LEGACY);
        }
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDisplayName(request.getDisplayName() != null ? request.getDisplayName() : request.getUsername());
        user.setOnlineStatus(User.OnlineStatus.OFFLINE);
        user.setIsActive(true);
        user.getRoles().add(User.Role.USER);

        User savedUser = userRepository.save(user);
        return convertToDto(savedUser);
    }

    /**
     * 根据用户名查找用户
     */
    public UserDto findByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return convertToDto(user);
    }

    /**
     * 根据用户名查找用户实体
     */
    public User findUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    /**
     * 根据ID查找用户
     */
    public UserDto findById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return convertToDto(user);
    }

    /**
     * 更新用户信息
     */
    @Transactional
    public UserDto updateUser(Long userId, UserDto.UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getOnlineStatus() != null) {
            user.setOnlineStatus(request.getOnlineStatus());
        }

        User savedUser = userRepository.save(user);
        return convertToDto(savedUser);
    }

    /**
     * 修改密码
     */
    @Transactional
    public void changePassword(Long userId, UserDto.ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        String scheme = normalizeScheme(user.getPasswordScheme());
        String oldCredential = oldCredentialForScheme(scheme, request);
        if (!passwordEncoder.matches(oldCredential, user.getPassword())) {
            throw new RuntimeException("旧密码不正确");
        }

        if (isNotBlank(request.getNewClientHash())) {
            validateClientCredentialBundle(request.getNewClientHash(), request.getNewClientSalt(), request.getNewArgon2Params());
            user.setPassword(passwordEncoder.encode(request.getNewClientHash()));
            user.setClientSalt(request.getNewClientSalt());
            user.setArgon2Params(request.getNewArgon2Params());
            user.setPasswordScheme(SCHEME_CLIENT);
        } else {
            user.setPassword(passwordEncoder.encode(request.getNewPassword()));
            user.setClientSalt(null);
            user.setArgon2Params(null);
            user.setPasswordScheme(SCHEME_LEGACY);
        }
        userRepository.save(user);
    }

    /**
     * 更新用户在线状态
     */
    @Transactional
    public void updateOnlineStatus(Long userId, User.OnlineStatus status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        user.setOnlineStatus(status);
        user.setLastSeen(LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * 更新用户头像
     */
    @Transactional
    public UserDto updateAvatar(Long userId, String avatarUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        user.setAvatarUrl(avatarUrl);
        User savedUser = userRepository.save(user);
        return convertToDto(savedUser);
    }

    @Transactional
    public UserDto updateTitle(Long userId, UserDto.TitleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setTitle(normalizeNullable(request.getTitle()));
        user.setTitleColor(normalizeTitleColor(request.getTitleColor()));
        user.setTitleEffect(normalizeTitleEffect(request.getTitleEffect()));
        return convertToDto(userRepository.save(user));
    }

    @Transactional
    public UserDto updateUserTitleAsAdmin(Long operatorId, Long targetUserId, UserDto.TitleRequest request) {
        User operator = userRepository.findById(operatorId)
                .orElseThrow(() -> new RuntimeException("操作者不存在"));
        if (!operator.getRoles().contains(User.Role.ADMIN)) {
            throw new org.springframework.security.access.AccessDeniedException("需要管理员权限");
        }
        return updateTitle(targetUserId, request);
    }

    /**
     * 搜索用户
     */
    public Page<UserDto> searchUsers(String keyword, Pageable pageable) {
        Page<User> users = userRepository.searchUsers(keyword, pageable);
        return users.map(this::convertToDto);
    }

    /**
     * 获取在线用户
     */
    public List<UserDto> getOnlineUsers() {
        List<User> users = userRepository.findByOnlineStatus(User.OnlineStatus.ONLINE);
        return users.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    /**
     * 统计在线用户数量
     */
    public long countOnlineUsers() {
        return userRepository.countOnlineUsers();
    }

    /**
     * 将User实体转换为UserDto
     */
    private UserDto convertToDto(User user) {
        UserDto dto = modelMapper.map(user, UserDto.class);
        userSettingsRepository.findByUserId(user.getId())
                .ifPresent(settings -> dto.setAvatarFramePreset(settings.getAvatarFramePreset()));
        return dto;
    }

    /**
     * Repairs the built-in admin account if its ADMIN role is missing.
     *
     * <p>This intentionally does not grant ADMIN during public registration.
     * It only applies to an existing username exactly equal to "admin".</p>
     *
     * @return true when the built-in admin user exists, false otherwise
     */
    @Transactional
    public boolean ensureBuiltinAdminRole() {
        User admin = userRepository.findByUsername("admin").orElse(null);
        if (admin == null) {
            return false;
        }
        if (!admin.getRoles().contains(User.Role.ADMIN)) {
            admin.getRoles().add(User.Role.ADMIN);
            userRepository.save(admin);
        }
        return true;
    }

    private String credentialForScheme(String scheme, UserDto.LoginRequest request) {
        if (SCHEME_CLIENT.equals(scheme)) {
            if (!isNotBlank(request.getClientHash())) {
                throw new ClientTooOldException("请升级客户端后再登录");
            }
            validateClientHash(request.getClientHash());
            return request.getClientHash();
        }
        if (!isNotBlank(request.getPassword())) {
            throw new PasswordUpgradeRequiredException("该账号需要使用旧客户端密码升级流程");
        }
        return request.getPassword();
    }

    private String oldCredentialForScheme(String scheme, UserDto.ChangePasswordRequest request) {
        if (SCHEME_CLIENT.equals(scheme)) {
            if (!isNotBlank(request.getOldClientHash())) {
                throw new ClientTooOldException("请升级客户端后再修改密码");
            }
            validateClientHash(request.getOldClientHash());
            return request.getOldClientHash();
        }
        if (!isNotBlank(request.getOldPassword())) {
            throw new PasswordUpgradeRequiredException("该账号需要旧密码才能升级");
        }
        return request.getOldPassword();
    }

    private void validateClientCredentialBundle(String clientHash, String clientSalt, String argon2Params) {
        validateClientHash(clientHash);
        validateClientSalt(clientSalt);
        validateArgon2Params(argon2Params);
    }

    private void validateClientHash(String clientHash) {
        if (!isNotBlank(clientHash) || clientHash.length() < 32 || clientHash.length() > 256) {
            throw new IllegalArgumentException("clientHash 格式不正确");
        }
        decodeBase64Url(clientHash, "clientHash");
    }

    private void validateClientSalt(String clientSalt) {
        byte[] salt = decodeBase64Url(clientSalt, "clientSalt");
        if (salt.length < 16 || salt.length > 64) {
            throw new IllegalArgumentException("clientSalt 长度不正确");
        }
    }

    private void validateArgon2Params(String argon2Params) {
        Matcher matcher = ARGON2_PARAMS_PATTERN.matcher(argon2Params == null ? "" : argon2Params);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("argon2Params 格式不正确");
        }
        int memoryKb = Integer.parseInt(matcher.group(1));
        int iterations = Integer.parseInt(matcher.group(2));
        int parallelism = Integer.parseInt(matcher.group(3));
        int version = Integer.parseInt(matcher.group(4));
        int hashLen = Integer.parseInt(matcher.group(5));
        if (memoryKb < 16_384 || memoryKb > 262_144
                || iterations < 1 || iterations > 10
                || parallelism < 1 || parallelism > 4
                || version != 19
                || hashLen < 16 || hashLen > 64) {
            throw new IllegalArgumentException("argon2Params 超出允许范围");
        }
    }

    private byte[] decodeBase64Url(String value, String fieldName) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " 必须是 base64url");
        }
    }

    private String deriveFakeSalt(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSaltHmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((username == null ? "" : username.trim()).getBytes(StandardCharsets.UTF_8));
            byte[] salt = new byte[16];
            System.arraycopy(digest, 0, salt, 0, salt.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(salt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成客户端 salt", e);
        }
    }

    private String normalizeScheme(String scheme) {
        return SCHEME_CLIENT.equals(scheme) ? SCHEME_CLIENT : SCHEME_LEGACY;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeNullable(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeTitleColor(String value) {
        String color = normalizeNullable(value);
        if (color == null) return null;
        if (!color.matches("^#[0-9a-fA-F]{6}$")) {
            throw new IllegalArgumentException("头衔颜色必须是 #RRGGBB");
        }
        return color;
    }

    private String normalizeTitleEffect(String value) {
        String effect = normalizeNullable(value);
        if (effect == null) return "none";
        if (!TITLE_EFFECTS.contains(effect)) {
            throw new IllegalArgumentException("不支持的头衔特效");
        }
        return effect;
    }

    /**
     * 验证用户密码
     */
    public boolean validatePassword(String username, String password) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !SCHEME_LEGACY.equals(normalizeScheme(user.getPasswordScheme()))) {
            return false;
        }
        return passwordEncoder.matches(password, user.getPassword());
    }
}
