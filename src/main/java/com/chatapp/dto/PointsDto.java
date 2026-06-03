package com.chatapp.dto;

import com.chatapp.entity.PointsLedgerEntry;
import com.chatapp.entity.RedemptionCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class PointsDto {
    private PointsDto() {}

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceResponse {
        @JsonProperty("free_remaining_per_feature")
        private Map<String, Integer> freeRemainingPerFeature;

        @JsonProperty("paid_points")
        private int paidPoints;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LedgerEntryResponse {
        private Long id;
        private int delta;
        private String reason;

        @JsonProperty("ref_key")
        private String refKey;

        @JsonProperty("ref_id")
        private String refId;

        @JsonProperty("balance_paid_after")
        private int balancePaidAfter;

        @JsonProperty("free_used")
        private int freeUsed;

        @JsonProperty("free_remaining_after")
        private Integer freeRemainingAfter;

        private String memo;

        @JsonProperty("created_at")
        private LocalDateTime createdAt;

        public static LedgerEntryResponse fromEntity(PointsLedgerEntry entry) {
            return new LedgerEntryResponse(
                    entry.getId(),
                    entry.getDelta() == null ? 0 : entry.getDelta(),
                    entry.getReason() == null ? null : entry.getReason().name().toLowerCase(),
                    entry.getRefKey(),
                    entry.getRefId(),
                    entry.getBalancePaidAfter() == null ? 0 : entry.getBalancePaidAfter(),
                    entry.getFreeUsed() == null ? 0 : entry.getFreeUsed(),
                    entry.getFreeRemainingAfter(),
                    entry.getMemo(),
                    entry.getCreatedAt()
            );
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostPreviewResponse {
        @JsonProperty("feature_key")
        private String featureKey;

        private int cost;

        @JsonProperty("free_remaining")
        private int freeRemaining;

        @JsonProperty("will_use_free")
        private boolean willUseFree;

        @JsonProperty("paid_points")
        private int paidPoints;

        @JsonProperty("paid_remaining_after")
        private int paidRemainingAfter;

        private boolean sufficient;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebitResult {
        @JsonProperty("used_free")
        private int usedFree;

        @JsonProperty("used_paid")
        private int usedPaid;

        @JsonProperty("balance_paid_after")
        private int balancePaidAfter;

        @JsonProperty("ledger_id")
        private Long ledgerId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedeemRequest {
        @NotBlank
        private String code;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedeemResponse {
        private int credited;

        @JsonProperty("new_paid_balance")
        private int newPaidBalance;

        @JsonProperty("code_memo")
        private String codeMemo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IssueCodesRequest {
        @Min(1)
        @Max(500)
        private int count;

        @JsonProperty("points_each")
        @Min(1)
        private int pointsEach;

        @JsonProperty("batch_label")
        private String batchLabel;

        @JsonProperty("expires_at")
        private LocalDateTime expiresAt;

        private String memo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IssueCodesResponse {
        private List<String> codes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminCreditRequest {
        @Min(1)
        private int points;

        private String reason;
        private String memo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeMetadataResponse {
        @JsonProperty("code_hash")
        private String codeHash;

        private int points;

        @JsonProperty("batch_label")
        private String batchLabel;

        private String memo;

        @JsonProperty("issued_by_user_id")
        private Long issuedByUserId;

        @JsonProperty("issued_at")
        private LocalDateTime issuedAt;

        @JsonProperty("expires_at")
        private LocalDateTime expiresAt;

        @JsonProperty("redeemed_by_user_id")
        private Long redeemedByUserId;

        @JsonProperty("redeemed_at")
        private LocalDateTime redeemedAt;

        public static CodeMetadataResponse fromEntity(RedemptionCode code) {
            return new CodeMetadataResponse(
                    code.getCodeHash(),
                    code.getPoints() == null ? 0 : code.getPoints(),
                    code.getBatchLabel(),
                    code.getMemo(),
                    code.getIssuedByUserId(),
                    code.getIssuedAt(),
                    code.getExpiresAt(),
                    code.getRedeemedByUserId(),
                    code.getRedeemedAt()
            );
        }
    }
}
