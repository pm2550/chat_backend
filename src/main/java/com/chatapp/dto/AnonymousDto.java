package com.chatapp.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousDto {

    private Long id;
    private String anonymousName;
    private String anonymousAvatar;
    private Boolean customNameUsed;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenameRequest {
        @Size(min = 2, max = 20, message = "匿名昵称长度2-20个字符")
        private String newName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnonymousMessageInfo {
        private String anonymousName;
        private String anonymousAvatar;
        private Boolean isAnonymous;
    }
}
