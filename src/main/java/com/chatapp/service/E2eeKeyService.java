package com.chatapp.service;

import com.chatapp.dto.E2eeDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.E2eeIdentityKey;
import com.chatapp.entity.E2eeUserState;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.E2eeIdentityKeyRepository;
import com.chatapp.repository.E2eeUserStateRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 私聊端到端加密的服务端部分：保管公钥目录和"包装过的私钥"，判断哪些会话可以加密，
 * 以及挡住不该出现的密文消息。
 *
 * <p>服务器不参与加解密。消息密文由客户端用 X25519(发送方, 接收方) + HKDF 派生的
 * AES-256-GCM 密钥加密，服务器只存 {@code encryptedContent}，{@code content} 固定写成
 * {@link #OLD_CLIENT_PLACEHOLDER}——老版本客户端直接显示这句话。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class E2eeKeyService {

    /** 当前唯一支持的消息加密格式（客户端 e2ee_crypto.dart 的 kE2eeEncryptionVersion）。 */
    public static final int MESSAGE_ENCRYPTION_VERSION = 2;
    /** 存进 content 的占位：老客户端不认识密文，就显示这句。 */
    public static final String OLD_CLIENT_PLACEHOLDER = "[加密消息，请更新到最新版本查看]";
    /** 推送、通知里用的文字，不带任何内容。 */
    public static final String NOTIFICATION_PLACEHOLDER = "[加密消息]";
    /** 附件的文件名同样可能是隐私，统一换成这个。 */
    public static final String ATTACHMENT_FILE_NAME = "加密附件（请更新到最新版本查看）";
    /** encrypted_content 是 BLOB（64KB），留些余量。 */
    public static final int MAX_ENVELOPE_BYTES = 60_000;

    public static final String REASON_OK = "OK";
    public static final String REASON_NOT_PRIVATE = "NOT_PRIVATE";
    public static final String REASON_NOT_TWO_MEMBERS = "NOT_TWO_MEMBERS";
    public static final String REASON_HAS_BOTS = "HAS_BOTS";

    /** 内置 Agent 的名字（和 ChatRoomService.ensureSystemAgentBinding 查的是同一个）。 */
    private static final String SYSTEM_AGENT_NAME = "Agent";

    private static final Pattern WRAP_PARAMS = Pattern.compile(
            "^m=(\\d+),t=(\\d+),p=(\\d+),v=(\\d+),hashLen=(\\d+)$");
    /** 私钥 32 字节 + GCM 标签 16 字节，前面 12 字节 IV。 */
    private static final int WRAPPED_PRIVATE_KEY_BYTES = 12 + 32 + 16;

    private final E2eeIdentityKeyRepository keyRepository;
    private final E2eeUserStateRepository stateRepository;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomBotRepository chatRoomBotRepository;
    private final BotConfigRepository botConfigRepository;

    @Transactional(readOnly = true)
    public E2eeDto.OwnKeys ownKeys(Long userId) {
        User user = requireUser(userId);
        E2eeUserState state = stateRepository.findById(userId).orElse(null);
        List<E2eeDto.OwnKey> keys = keyRepository.findByUserIdOrderByKeyVersionAsc(userId).stream()
                .map(key -> new E2eeDto.OwnKey(
                        key.getKeyVersion(),
                        key.getPublicKey(),
                        key.getWrappedPrivateKey(),
                        key.getWrapSalt(),
                        key.getWrapParams()))
                .toList();
        return new E2eeDto.OwnKeys(
                isEnabled(state, keys.size()),
                state != null ? state.getActiveKeyVersion() : null,
                UserService.SCHEME_CLIENT.equals(user.getPasswordScheme()),
                keys);
    }

    /** 公钥目录：公钥本来就是公开的，登录用户都能查。 */
    @Transactional(readOnly = true)
    public E2eeDto.UserKeys userKeys(Long userId) {
        requireUser(userId);
        E2eeUserState state = stateRepository.findById(userId).orElse(null);
        List<E2eeDto.PublicKey> keys = keyRepository.findByUserIdOrderByKeyVersionAsc(userId).stream()
                .map(key -> new E2eeDto.PublicKey(key.getKeyVersion(), key.getPublicKey(), key.getCreatedAt()))
                .toList();
        return new E2eeDto.UserKeys(
                userId,
                isEnabled(state, keys.size()),
                state != null ? state.getActiveKeyVersion() : null,
                keys);
    }

    /**
     * 新建一把身份密钥并设为当前版本，同时打开加密。旧版本保留（读历史要用）。
     *
     * <p>{@code expectedActiveKeyVersion} 防止两台设备同时开启、各生成一把：
     * 后到的那台会被拒绝，刷新后改为解锁已有的那把。</p>
     */
    @Transactional
    public E2eeDto.OwnKeys createKey(Long userId, E2eeDto.CreateKeyRequest request) {
        User user = requireUser(userId);
        if (!UserService.SCHEME_CLIENT.equals(user.getPasswordScheme())) {
            // 旧式登录把明文密码发给服务器，密码派生的包装密钥就不再是秘密。
            throw new IllegalArgumentException("请先在新版客户端修改一次登录密码，再开启端到端加密");
        }
        if (request == null) {
            throw new IllegalArgumentException("缺少密钥");
        }
        requireBase64Length(request.getPublicKey(), 32, 32, "公钥格式不正确");
        requireWrap(request.getWrappedPrivateKey(), request.getWrapSalt(), request.getWrapParams());

        E2eeUserState state = stateRepository.findById(userId).orElseGet(() -> {
            E2eeUserState created = new E2eeUserState();
            created.setUserId(userId);
            return created;
        });
        Integer expected = request.getExpectedActiveKeyVersion();
        Integer active = state.getActiveKeyVersion();
        if (active == null ? expected != null : !active.equals(expected)) {
            throw new IllegalStateException("加密密钥已在其他设备上更新，请刷新后重试");
        }

        int nextVersion = keyRepository.findByUserIdOrderByKeyVersionAsc(userId).stream()
                .mapToInt(E2eeIdentityKey::getKeyVersion)
                .max()
                .orElse(0) + 1;
        if (request.getKeyVersion() != null && request.getKeyVersion() != nextVersion) {
            throw new IllegalStateException("加密密钥已在其他设备上更新，请刷新后重试");
        }
        E2eeIdentityKey key = new E2eeIdentityKey();
        key.setUserId(userId);
        key.setKeyVersion(nextVersion);
        key.setPublicKey(request.getPublicKey());
        key.setWrappedPrivateKey(request.getWrappedPrivateKey());
        key.setWrapSalt(request.getWrapSalt());
        key.setWrapParams(request.getWrapParams());
        keyRepository.save(key);

        state.setActiveKeyVersion(nextVersion);
        state.setEnabled(true);
        stateRepository.save(state);
        log.info("用户 {} 生成了端到端加密身份密钥 v{}", userId, nextVersion);
        return ownKeys(userId);
    }

    /**
     * 开关只影响"以后的消息加不加密"。关掉时密钥保留，已加密的历史仍然能读；
     * 再打开时沿用原来的密钥，不用重新生成。
     */
    @Transactional
    public E2eeDto.OwnKeys setEnabled(Long userId, boolean enabled) {
        requireUser(userId);
        E2eeUserState state = stateRepository.findById(userId).orElse(null);
        if (enabled && (state == null || state.getActiveKeyVersion() == null)) {
            throw new IllegalArgumentException("还没有加密密钥，请先生成");
        }
        if (state != null) {
            state.setEnabled(enabled);
            stateRepository.save(state);
        }
        return ownKeys(userId);
    }

    /**
     * 改密码时一起换掉私钥的包装。和改密码在同一个事务里：要么都成功，要么都不变，
     * 不会出现"密码改了、私钥还用旧密码包着，别的设备再也解不开"的情况。
     *
     * <p>用户有密钥时必须带上新包装且至少包含当前版本——老客户端改密码不会带，
     * 直接拒绝，免得用户在老版本上改个密码就丢了全部加密历史。</p>
     */
    @Transactional
    public void rewrapForPasswordChange(Long userId, List<E2eeDto.KeyWrap> wraps) {
        List<E2eeIdentityKey> keys = keyRepository.findByUserIdOrderByKeyVersionAsc(userId);
        if (keys.isEmpty()) {
            return;
        }
        if (wraps == null || wraps.isEmpty()) {
            throw new IllegalArgumentException("你已开启端到端加密，请用最新版本客户端修改密码，否则加密聊天记录将无法解密");
        }
        Map<Integer, E2eeIdentityKey> byVersion = new HashMap<>();
        keys.forEach(key -> byVersion.put(key.getKeyVersion(), key));
        Integer active = stateRepository.findById(userId)
                .map(E2eeUserState::getActiveKeyVersion)
                .orElse(null);
        if (active != null && wraps.stream().noneMatch(wrap -> active.equals(wrap.getVersion()))) {
            throw new IllegalArgumentException("缺少当前加密密钥的新包装");
        }
        for (E2eeDto.KeyWrap wrap : wraps) {
            E2eeIdentityKey key = wrap == null ? null : byVersion.get(wrap.getVersion());
            if (key == null) {
                throw new IllegalArgumentException("加密密钥版本不存在");
            }
            requireWrap(wrap.getWrappedPrivateKey(), wrap.getWrapSalt(), wrap.getWrapParams());
            key.setWrappedPrivateKey(wrap.getWrappedPrivateKey());
            key.setWrapSalt(wrap.getWrapSalt());
            key.setWrapParams(wrap.getWrapParams());
        }
        keyRepository.saveAll(keys);
        log.info("用户 {} 改密码时重新包装了 {} 把加密密钥", userId, wraps.size());
    }

    /** 会话能否加密 + 双方的公钥目录。只有成员能查。 */
    @Transactional(readOnly = true)
    public E2eeDto.RoomStatus roomStatus(Long roomId, Long viewerId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("聊天室不存在"));
        List<Long> members = chatRoomRepository.findMemberUserIdsByRoomId(roomId);
        if (members == null || !members.contains(viewerId)) {
            throw new AccessDeniedException("您不是该聊天室的成员");
        }
        String reason = ineligibilityReason(room, members);
        E2eeDto.UserKeys peer = null;
        if (members.size() == 2) {
            Long peerId = members.get(0).equals(viewerId) ? members.get(1) : members.get(0);
            peer = userKeys(peerId);
        }
        return new E2eeDto.RoomStatus(
                roomId,
                reason == null,
                reason == null ? REASON_OK : reason,
                userKeys(viewerId),
                peer);
    }

    /**
     * 发一条密文消息（或把消息编辑成密文）之前的检查，返回要存的密文字节。
     * 只允许两个真人的私聊、没有机器人：机器人读不了密文，群聊也不做加密。
     */
    public byte[] requireEncryptableMessage(ChatRoom room,
                                            String encryptedContentBase64,
                                            Integer encryptionVersion,
                                            boolean anonymous) {
        if (encryptionVersion == null || encryptionVersion != MESSAGE_ENCRYPTION_VERSION) {
            throw new IllegalArgumentException("不支持的加密格式，请更新到最新版本");
        }
        if (anonymous) {
            throw new IllegalArgumentException("匿名消息不能端到端加密");
        }
        String reason = ineligibilityReason(room, chatRoomRepository.findMemberUserIdsByRoomId(room.getId()));
        if (reason != null) {
            throw new IllegalArgumentException(REASON_HAS_BOTS.equals(reason)
                    ? "会话里有机器人，不能发送端到端加密消息"
                    : "只有两人私聊可以发送端到端加密消息");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encryptedContentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("加密内容必须是有效 Base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("加密消息太长");
        }
        return bytes;
    }

    /** 这条消息是否是端到端加密的（服务器只有密文和占位文字）。 */
    public static boolean isEncrypted(Message message) {
        return message != null
                && message.getEncryptedContent() != null
                && message.getEncryptedContent().length > 0;
    }

    String ineligibilityReason(ChatRoom room, List<Long> members) {
        if (room.getRoomType() != ChatRoom.RoomType.PRIVATE) {
            return REASON_NOT_PRIVATE;
        }
        if (members == null || members.size() != 2) {
            return REASON_NOT_TWO_MEMBERS;
        }
        Long systemAgentId = botConfigRepository.findFirstByBotNameAndCreatedByIsNullOrderByIdAsc(SYSTEM_AGENT_NAME)
                .map(BotConfig::getId)
                .orElse(null);
        boolean hasBots = chatRoomBotRepository.findActiveBotsWithConfig(room.getId()).stream()
                .anyMatch(binding -> !isPassiveSystemAgent(binding, systemAgentId));
        return hasBots ? REASON_HAS_BOTS : null;
    }

    /**
     * 每个会话建好时都会自动挂上内置的 Agent（ChatRoomService.ensureSystemAgentBinding），
     * 只在被 @ 时才读那一条消息。加密消息服务器看不到 @，它也就永远不会被触发、读不到内容，
     * 所以不算"会话里有机器人"——否则所有私聊都开不了加密。用户自己拉进来的机器人、
     * 或把 Agent 改成关键词/全部触发，都算。
     */
    private boolean isPassiveSystemAgent(ChatRoomBot binding, Long systemAgentId) {
        return systemAgentId != null
                && binding.getBotConfig() != null
                && systemAgentId.equals(binding.getBotConfig().getId())
                && binding.getTriggerMode() == ChatRoomBot.TriggerMode.MENTION;
    }

    private boolean isEnabled(E2eeUserState state, int keyCount) {
        return state != null
                && Boolean.TRUE.equals(state.getEnabled())
                && state.getActiveKeyVersion() != null
                && keyCount > 0;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void requireWrap(String wrappedPrivateKey, String wrapSalt, String wrapParams) {
        requireBase64Length(wrappedPrivateKey, WRAPPED_PRIVATE_KEY_BYTES, WRAPPED_PRIVATE_KEY_BYTES,
                "加密私钥格式不正确");
        requireBase64Length(wrapSalt, 16, 48, "密钥盐格式不正确");
        Matcher matcher = wrapParams == null ? null : WRAP_PARAMS.matcher(wrapParams);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("密钥派生参数格式不正确");
        }
        // 只做底线检查：太弱的参数会让拿到数据库的人轻易暴力猜出密码。
        long memoryKb = Long.parseLong(matcher.group(1));
        long iterations = Long.parseLong(matcher.group(2));
        long hashLen = Long.parseLong(matcher.group(5));
        if (memoryKb < 19_456 || memoryKb > 1_048_576 || iterations < 2 || iterations > 10 || hashLen != 32) {
            throw new IllegalArgumentException("密钥派生参数强度不符合要求");
        }
    }

    private void requireBase64Length(String value, int min, int max, String message) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(message);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message);
        }
        if (bytes.length < min || bytes.length > max) {
            throw new IllegalArgumentException(message);
        }
    }
}
