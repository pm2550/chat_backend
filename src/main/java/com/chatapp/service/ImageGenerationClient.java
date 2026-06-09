package com.chatapp.service;

public interface ImageGenerationClient {
    SubmitResult submit(String apiKey, String prompt, int count, String size);

    default SubmitResult submit(String apiKey, String prompt, int count, String size, boolean expand) {
        return submit(apiKey, prompt, count, size);
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
