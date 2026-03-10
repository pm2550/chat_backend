package com.chatapp.service;

import com.chatapp.dto.AnonymousDto;
import com.chatapp.entity.AnonymousIdentity;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.repository.AnonymousIdentityRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnonymousService {

    private final AnonymousIdentityRepository anonymousIdentityRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    private static final String[] ADJECTIVES = {
        "神秘", "快乐", "安静", "勇敢", "智慧", "优雅", "闪亮", "温柔",
        "活泼", "沉稳", "飘逸", "灵动", "悠然", "淡定", "傲娇", "呆萌",
        "高冷", "热情", "机智", "可爱", "酷酷", "慵懒", "文艺", "硬核",
        "甜蜜", "霸气", "低调", "闷骚", "佛系", "狂野", "清新", "暖心"
    };

    private static final String[] ANIMALS = {
        "海豚", "白兔", "熊猫", "狐狸", "猫头鹰", "企鹅", "考拉", "柴犬",
        "仓鼠", "松鼠", "水獭", "羊驼", "猫咪", "小鹿", "刺猬", "海龟",
        "鹦鹉", "蝴蝶", "独角兽", "龙猫", "小象", "树懒", "浣熊", "白鸽",
        "金鱼", "萤火虫", "雪狐", "蜜蜂", "青蛙", "天鹅", "孔雀", "麋鹿"
    };

    private static final String[] AVATAR_COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7",
        "#DDA0DD", "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E9",
        "#F1948A", "#82E0AA", "#F8C471", "#AED6F1", "#D2B4DE"
    };

    @Transactional
    public AnonymousDto getOrCreateIdentity(Long userId, Long chatRoomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        if (!chatRoom.getAnonymousEnabled()) {
            throw new IllegalArgumentException("该聊天室未开启匿名功能");
        }

        LocalDate today = LocalDate.now();
        AnonymousIdentity identity = anonymousIdentityRepository
                .findByUserIdAndChatRoomIdAndAssignedDate(userId, chatRoomId, today)
                .orElseGet(() -> createNewIdentity(userId, chatRoomId, today));

        return toDto(identity);
    }

    private AnonymousIdentity createNewIdentity(Long userId, Long chatRoomId, LocalDate date) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        AnonymousIdentity identity = new AnonymousIdentity();
        identity.setUser(user);
        identity.setChatRoom(chatRoom);
        identity.setAnonymousName(generateRandomName());
        identity.setAnonymousAvatar(generateRandomAvatar());
        identity.setAssignedDate(date);
        identity.setCustomNameUsed(false);

        identity = anonymousIdentityRepository.save(identity);
        log.info("为用户 {} 在聊天室 {} 创建匿名身份: {}", userId, chatRoomId, identity.getAnonymousName());
        return identity;
    }

    @Transactional
    public AnonymousDto renameAnonymousIdentity(Long userId, Long chatRoomId, String newName) {
        LocalDate today = LocalDate.now();
        AnonymousIdentity identity = anonymousIdentityRepository
                .findByUserIdAndChatRoomIdAndAssignedDate(userId, chatRoomId, today)
                .orElseThrow(() -> new RuntimeException("未找到匿名身份，请先进入匿名模式"));

        if (identity.getCustomNameUsed()) {
            throw new IllegalArgumentException("今日已使用过改名机会，明天再试");
        }

        identity.setAnonymousName(newName);
        identity.setCustomNameUsed(true);
        identity = anonymousIdentityRepository.save(identity);

        log.info("用户 {} 在聊天室 {} 改名为: {}", userId, chatRoomId, newName);
        return toDto(identity);
    }

    @Transactional
    public void toggleAnonymous(Long chatRoomId, Long operatorId, boolean enable) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        if (!chatRoomRepository.isAdmin(chatRoomId, operatorId)) {
            throw new IllegalArgumentException("只有管理员可以切换匿名功能");
        }

        chatRoom.setAnonymousEnabled(enable);
        chatRoomRepository.save(chatRoom);
        log.info("聊天室 {} 匿名功能已{}", chatRoomId, enable ? "开启" : "关闭");
    }

    public AnonymousDto.AnonymousMessageInfo getAnonymousInfoForMessage(Long anonymousIdentityId) {
        if (anonymousIdentityId == null) {
            return null;
        }
        AnonymousIdentity identity = anonymousIdentityRepository.findById(anonymousIdentityId)
                .orElse(null);
        if (identity == null) {
            return null;
        }
        AnonymousDto.AnonymousMessageInfo info = new AnonymousDto.AnonymousMessageInfo();
        info.setAnonymousName(identity.getAnonymousName());
        info.setAnonymousAvatar(identity.getAnonymousAvatar());
        info.setIsAnonymous(true);
        return info;
    }

    @Scheduled(cron = "0 0 2 * * ?") // Run at 2 AM daily
    @Transactional
    public void cleanupOldIdentities() {
        LocalDate cutoff = LocalDate.now().minusDays(7);
        int deleted = anonymousIdentityRepository.deleteOldIdentities(cutoff);
        if (deleted > 0) {
            log.info("清理了 {} 条过期匿名身份", deleted);
        }
    }

    private String generateRandomName() {
        String adj = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        String animal = ANIMALS[random.nextInt(ANIMALS.length)];
        return adj + animal;
    }

    private String generateRandomAvatar() {
        return AVATAR_COLORS[random.nextInt(AVATAR_COLORS.length)];
    }

    private AnonymousDto toDto(AnonymousIdentity entity) {
        AnonymousDto dto = new AnonymousDto();
        dto.setId(entity.getId());
        dto.setAnonymousName(entity.getAnonymousName());
        dto.setAnonymousAvatar(entity.getAnonymousAvatar());
        dto.setCustomNameUsed(entity.getCustomNameUsed());
        return dto;
    }
}
