package com.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 私聊端到端加密的接口数据。服务器只经手公钥和"用密码派生密钥包装过的私钥"，
 * 永远拿不到明文私钥，也拿不到能解开它的密钥。
 */
public class E2eeDto {

    private E2eeDto() {
    }

    /** 公开的一把身份公钥。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublicKey {
        private Integer version;
        private String publicKey;
        private LocalDateTime createdAt;
    }

    /** 某个用户的公钥目录：别人据此加密、校验他以前发的消息。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserKeys {
        private Long userId;
        /** 开着并且有当前密钥：别人给他发私聊时应当加密。 */
        private boolean enabled;
        private Integer activeKeyVersion;
        private List<PublicKey> keys;
    }

    /** 本人的一把密钥：公钥 + 包装过的私钥，只返回给本人。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnKey {
        private Integer version;
        private String publicKey;
        private String wrappedPrivateKey;
        private String wrapSalt;
        private String wrapParams;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnKeys {
        private boolean enabled;
        private Integer activeKeyVersion;
        /**
         * 账号是否已经是"客户端哈希"登录：旧式登录会把明文密码发给服务器，
         * 那样密码派生的包装密钥就不再只有用户自己知道，必须先升级。
         */
        private boolean passwordSchemeSupported;
        private List<OwnKey> keys;
    }

    /** 新建一把身份密钥（首次开启，或密码被重置后重新生成）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateKeyRequest {
        private String publicKey;
        private String wrappedPrivateKey;
        private String wrapSalt;
        private String wrapParams;
        /** 客户端看到的当前版本（没有就是 null）；和服务器不一致说明别的设备刚换过密钥。 */
        private Integer expectedActiveKeyVersion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SetEnabledRequest {
        private Boolean enabled;
    }

    /** 改密码时用新密码重新包装的一把私钥。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyWrap {
        private Integer version;
        private String wrappedPrivateKey;
        private String wrapSalt;
        private String wrapParams;
    }

    /**
     * 某个会话能不能端到端加密。只有两个真人的私聊、没有机器人时才可以；
     * peer 是对方的公钥目录，客户端直接拿来加密。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomStatus {
        private Long roomId;
        private boolean eligible;
        /** OK / NOT_PRIVATE / NOT_TWO_MEMBERS / HAS_BOTS */
        private String reason;
        private UserKeys self;
        private UserKeys peer;
    }
}
