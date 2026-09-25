package com.chatapp.util;

import java.util.Locale;
import java.util.Set;

/**
 * Android 安装包按 CPU 架构拆成两个 APK（整包带两套原生库超过 100MB，上传会被拒、用户每次更新也要下 100MB）。
 *
 * <ul>
 *   <li>{@link #UNIVERSAL}（空串）：不分架构的整包——拆包前的旧发布，以及所有非 Android 平台。</li>
 *   <li>{@link #ARM64}：64 位手机，近几年的手机基本都是，也是旧客户端（≤1.1.51，不会报自己的架构）的默认。</li>
 *   <li>{@link #ARMEABI_V7A}：只能跑 32 位的旧手机。</li>
 * </ul>
 * x86_64 只有模拟器用，不发布。
 */
public final class AndroidAbi {

    public static final String UNIVERSAL = "";
    public static final String ARM64 = "arm64-v8a";
    public static final String ARMEABI_V7A = "armeabi-v7a";

    /**
     * 旧客户端不带 abi：给 64 位包。绝大多数手机是 64 位；只支持 32 位的旧手机会装失败
     * （系统安装器会报"与设备不兼容"），这些用户可以去下载页拿 32 位包。
     */
    public static final String LEGACY_DEFAULT = ARM64;

    private static final Set<String> PUBLISHED = Set.of(ARM64, ARMEABI_V7A);

    private AndroidAbi() {
    }

    /**
     * 规范化客户端/CI 传来的 abi。空值返回 {@link #UNIVERSAL}；不认识的架构抛
     * {@link IllegalArgumentException}（带中文说明，直接回给调用方）。
     */
    public static String normalize(String abi) {
        if (abi == null || abi.isBlank()) {
            return UNIVERSAL;
        }
        String value = abi.trim().toLowerCase(Locale.ROOT);
        if (!PUBLISHED.contains(value)) {
            throw new IllegalArgumentException("不支持的 Android 架构: " + abi.trim());
        }
        return value;
    }

    /** 数据库里用空串表示整包，对外（JSON）用 null。 */
    public static String toApi(String abi) {
        return abi == null || abi.isEmpty() ? null : abi;
    }
}
