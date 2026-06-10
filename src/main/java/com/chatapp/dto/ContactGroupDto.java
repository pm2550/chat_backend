package com.chatapp.dto;

import com.chatapp.entity.ContactGroup;
import com.chatapp.entity.ContactGroupItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class ContactGroupDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupSummary {
        private Long id;
        private String name;
        private Integer sortOrder;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static GroupSummary fromEntity(ContactGroup group) {
            return new GroupSummary(
                    group.getId(),
                    group.getName(),
                    group.getSortOrder(),
                    group.getCreatedAt(),
                    group.getUpdatedAt());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemAssignment {
        private Long groupId;
        private String targetType;
        private Long targetId;
        private LocalDateTime updatedAt;

        public static ItemAssignment fromEntity(ContactGroupItem item) {
            return new ItemAssignment(
                    item.getGroup().getId(),
                    item.getTargetType().name(),
                    item.getTargetId(),
                    item.getUpdatedAt());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bundle {
        private List<GroupSummary> groups;
        private List<ItemAssignment> assignments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpsertGroupRequest {
        @NotBlank
        private String name;
        private Integer sortOrder;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReorderRequest {
        @NotEmpty
        private List<Long> groupIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignItemRequest {
        @NotBlank
        private String targetType;
        @NotNull
        private Long targetId;
        private Long groupId;
    }
}
