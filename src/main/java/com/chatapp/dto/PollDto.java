package com.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PollDto {
    private Long id;
    private Long messageId;
    private String question;
    private List<OptionInfo> options;
    private Boolean multiSelect;
    private Boolean anonymous;
    private LocalDateTime expiresAt;
    private Integer totalVotes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionInfo {
        private Integer index;
        private String text;
        private Integer votes;
        private List<Long> voterIds;
    }

    @Data
    public static class CreateRequest {
        private Long chatRoomId;
        private String question;
        private List<String> options;
        private Boolean multiSelect;
        private Boolean anonymous;
        private LocalDateTime expiresAt;
    }

    @Data
    public static class VoteRequest {
        private List<Integer> optionIndexes;
    }
}
