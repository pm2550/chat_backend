package com.chatapp.dto;

import com.chatapp.entity.MessageReadReceipt;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceiptDto {
    private Long userId;
    private String username;
    private String displayName;
    private String avatarUrl;
    private LocalDateTime readAt;

    public static ReadReceiptDto fromEntity(MessageReadReceipt receipt) {
        var user = receipt.getUser();
        return new ReadReceiptDto(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                receipt.getReadAt());
    }
}
