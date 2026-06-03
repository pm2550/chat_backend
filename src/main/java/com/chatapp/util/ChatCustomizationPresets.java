package com.chatapp.util;

import java.util.Set;
import java.util.regex.Pattern;

public final class ChatCustomizationPresets {

    public static final String DEFAULT_BACKGROUND = "cloud_gradient";
    public static final String DEFAULT_AVATAR_FRAME = "none";
    public static final String DEFAULT_BUBBLE_STYLE = "default_gradient";

    public static final Set<String> BACKGROUNDS = Set.of(
            "cloud_gradient",
            "pixel_mint",
            "sunset_warm",
            "cyber_dark",
            "paper_dotted",
            "gradient_wave",
            "mono_lines",
            "aurora"
    );
    private static final Pattern SOLID_BACKGROUND_PATTERN =
            Pattern.compile("^solid:#[0-9A-Fa-f]{6}$");

    public static final Set<String> AVATAR_FRAMES = Set.of(
            "none",
            "pixel_pink",
            "golden_ring",
            "starry_night",
            "mint_minimal",
            "flame",
            "cyber_glow",
            "retro_dashes"
    );

    public static final Set<String> BUBBLE_STYLES = Set.of(
            "default_gradient",
            "minimal_flat",
            "rounded_soft",
            "retro_block",
            "dark_night",
            "high_contrast"
    );

    private ChatCustomizationPresets() {
    }

    public static String requireBackground(String preset) {
        String normalized = preset == null ? "" : preset.trim();
        if (SOLID_BACKGROUND_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        return requirePreset("chatBackgroundPreset", normalized, BACKGROUNDS);
    }

    public static String requireAvatarFrame(String preset) {
        return requirePreset("avatarFramePreset", preset, AVATAR_FRAMES);
    }

    public static String requireBubbleStyle(String preset) {
        return requirePreset("bubbleStylePreset", preset, BUBBLE_STYLES);
    }

    private static String requirePreset(String fieldName, String preset, Set<String> allowed) {
        String normalized = preset == null ? "" : preset.trim();
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(fieldName + " 不支持: " + preset);
        }
        return normalized;
    }
}
