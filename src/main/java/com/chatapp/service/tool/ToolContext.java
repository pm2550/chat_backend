package com.chatapp.service.tool;

/**
 * Execution context handed to every {@link Tool}. {@code botConfigId} identifies the
 * bot whose agent loop is invoking the tool, so capability-scoped tools (memory,
 * workspace, moderation) can resolve the calling bot. It may be {@code null} for
 * contexts not bound to a specific bot.
 */
public record ToolContext(Long roomId, Long userId, Long taskId, Long botConfigId) {

    /** Backward-compatible constructor for contexts without a bound bot. */
    public ToolContext(Long roomId, Long userId, Long taskId) {
        this(roomId, userId, taskId, null);
    }
}
