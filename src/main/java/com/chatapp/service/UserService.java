package com.chatapp.service;

import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户服务类
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ModelMapper modelMapper;

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

    /**
     * 用户注册
     */
    @Transactional
    public UserDto registerUser(UserDto.RegisterRequest request) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("邮箱已存在");
        }

        // 创建新用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
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

        // 验证旧密码
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new RuntimeException("旧密码不正确");
        }

        // 设置新密码
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
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
        return modelMapper.map(user, UserDto.class);
    }

    /**
     * 验证用户密码
     */
    public boolean validatePassword(String username, String password) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return false;
        }
        return passwordEncoder.matches(password, user.getPassword());
    }
} 