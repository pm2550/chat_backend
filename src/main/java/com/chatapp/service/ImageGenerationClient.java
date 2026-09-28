package com.chatapp.service;

public interface ImageGenerationClient {
    /** 扩写档位：按原文出图。 */
    String PROMPT_HELPER_OFF = "off";
    /** 扩写档位：只把描述翻译给画图模型，不改写。 */
    String PROMPT_HELPER_LOW = "low";
    /** 扩写档位：自动补充细节和画面感（默认）。 */
    String PROMPT_HELPER_MEDIUM = "medium";

    SubmitResult submit(String apiKey, String prompt, int count, String size);

    default SubmitResult submit(String apiKey, String prompt, int count, String size, boolean expand) {
        return submit(apiKey, prompt, count, size);
    }

    /** 按扩写档位提交；只认开/关的渠道把 off 当"不扩写"，其余档位都当"扩写"。 */
    default SubmitResult submit(String apiKey, String prompt, int count, String size, String promptHelperLevel) {
        return submit(apiKey, prompt, count, size, !PROMPT_HELPER_OFF.equals(promptHelperLevel));
    }

    PollResult poll(String apiKey, String taskId);

    byte[] download(String imageUrl);

    record SubmitResult(String taskId) {
    }

    record PollResult(Status status, String imageUrl, String errorMessage) {
        public enum Status {
            PENDING,
            RUNNING,
            SUCCEEDED,
            FAILED
        }
    }
}
