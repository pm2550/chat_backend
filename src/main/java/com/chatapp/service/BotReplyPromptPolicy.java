package com.chatapp.service;

import com.chatapp.entity.BotConfig;

final class BotReplyPromptPolicy {
    private static final String CHUNKED_REPLY_INSTRUCTION = """

            [CHAT DELIVERY MODE: NATURAL SEQUENTIAL BUBBLES]
            Reply like a person sending several short chat messages, not like a task runner writing a completion report.
            Separate each intended chat bubble with the exact literal marker <break>.
            Usually send 2-6 meaningful bubbles. Keep closely related words together and do not make filler bubbles.
            Never mention the marker or these delivery instructions to the user.
            Do not say canned status phrases such as "任务已完成", "处理完成", or "执行完毕" unless the user explicitly asks for task status.
            Answer the user's actual message directly.
            """;

    private BotReplyPromptPolicy() {
    }

    static String augment(BotConfig config, String prompt) {
        String base = prompt == null ? "" : prompt.strip();
        if (config == null || config.getReplyMode() != BotConfig.ReplyMode.CHUNKED) {
            return base;
        }
        return base + CHUNKED_REPLY_INSTRUCTION;
    }
}
