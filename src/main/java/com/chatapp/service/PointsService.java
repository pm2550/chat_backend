package com.chatapp.service;

import com.chatapp.dto.PointsDto;
import com.chatapp.entity.DailyFeatureUsage;
import com.chatapp.entity.FeatureCost;
import com.chatapp.entity.PointsLedgerEntry;
import com.chatapp.entity.UserBalance;
import com.chatapp.exception.InsufficientPointsException;
import com.chatapp.exception.PointsException;
import com.chatapp.repository.DailyFeatureUsageRepository;
import com.chatapp.repository.FeatureCostRepository;
import com.chatapp.repository.PointsLedgerRepository;
import com.chatapp.repository.UserBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class PointsService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final UserBalanceRepository userBalanceRepository;
    private final DailyFeatureUsageRepository usageRepository;
    private final FeatureCostRepository featureCostRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final TransactionTemplate transactionTemplate;
    private final Object[] userPointLocks = createUserPointLocks();

    public PointsDto.DebitResult debit(Long userId, String featureKey, String refId) {
        return withUserPointLock(userId, () -> inTransaction(() -> debitInTransaction(userId, featureKey, refId)));
    }

    private PointsDto.DebitResult debitInTransaction(Long userId, String featureKey, String refId) {
        requireLedgerRefId(refId, "扣点流水 refId 不能为空");
        FeatureCost feature = requireEnabledFeature(featureKey);
        UserBalance balance = getOrCreateLockedBalance(userId);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        DailyFeatureUsage usage = getOrCreateLockedUsage(userId, featureKey, today);

        int cost = nonNegative(feature.getCostPoints());
        int freeQuota = nonNegative(feature.getFreeDailyQuota());
        int usedToday = nonNegative(usage.getCount());
        int freeRemaining = Math.max(0, freeQuota - usedToday);
        int usedFree = freeRemaining > 0 ? 1 : 0;
        int usedPaid = usedFree > 0 ? 0 : cost;

        if (usedPaid > nonNegative(balance.getPaidPoints())) {
            throw new InsufficientPointsException("积分不足");
        }

        if (usedFree > 0) {
            usage.setCount(usedToday + usedFree);
            usageRepository.save(usage);
            freeRemaining = Math.max(0, freeQuota - usage.getCount());
        }

        if (usedPaid > 0) {
            balance.setPaidPoints(balance.getPaidPoints() - usedPaid);
            balance = userBalanceRepository.save(balance);
        }

        PointsLedgerEntry entry = new PointsLedgerEntry();
        entry.setUserId(userId);
        entry.setDelta(-usedPaid);
        entry.setReason(PointsLedgerEntry.LedgerReason.FEATURE_DEBIT);
        entry.setRefKey(featureKey);
        entry.setRefId(refId);
        entry.setBalancePaidAfter(nonNegative(balance.getPaidPoints()));
        entry.setFreeUsed(usedFree);
        entry.setFreeRemainingAfter(freeRemaining);
        entry.setMemo(usedFree > 0 ? "使用免费额度" : "扣除付费积分");
        entry = ledgerRepository.save(entry);

        return new PointsDto.DebitResult(
                usedFree,
                usedPaid,
                nonNegative(balance.getPaidPoints()),
                entry.getId()
        );
    }

    public void refund(Long userId, String featureKey, String refId, String reason) {
        withUserPointLock(userId, () -> inTransaction(() -> {
            refundInTransaction(userId, featureKey, refId, reason);
            return null;
        }));
    }

    private void refundInTransaction(Long userId, String featureKey, String refId, String reason) {
        requireLedgerRefId(refId, "退款流水 refId 不能为空");
        if (ledgerRepository.existsByUserIdAndRefKeyAndRefIdAndReason(
                userId, featureKey, refId, PointsLedgerEntry.LedgerReason.FEATURE_REFUND)) {
            return;
        }

        PointsLedgerEntry original = ledgerRepository
                .findFirstByUserIdAndRefKeyAndRefIdAndReasonOrderByCreatedAtDesc(
                        userId, featureKey, refId, PointsLedgerEntry.LedgerReason.FEATURE_DEBIT)
                .orElseThrow(() -> new PointsException(HttpStatus.NOT_FOUND, "未找到可退款的扣点记录"));

        UserBalance balance = getOrCreateLockedBalance(userId);
        int originalDelta = original.getDelta() == null ? 0 : original.getDelta();
        int refundPaid = Math.max(0, -originalDelta);
        int freeRestored = nonNegative(original.getFreeUsed());
        Integer freeRemainingAfter = null;

        if (refundPaid > 0) {
            balance.setPaidPoints(nonNegative(balance.getPaidPoints()) + refundPaid);
            balance = userBalanceRepository.save(balance);
        }

        if (freeRestored > 0) {
            LocalDate originalDate = original.getCreatedAt() == null
                    ? LocalDate.now(BUSINESS_ZONE)
                    : original.getCreatedAt().atZone(BUSINESS_ZONE).toLocalDate();
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            if (today.equals(originalDate)) {
                FeatureCost feature = requireEnabledFeature(featureKey);
                DailyFeatureUsage usage = getOrCreateLockedUsage(userId, featureKey, today);
                usage.setCount(Math.max(0, nonNegative(usage.getCount()) - freeRestored));
                usageRepository.save(usage);
                freeRemainingAfter = Math.max(0, nonNegative(feature.getFreeDailyQuota()) - usage.getCount());
            }
        }

        PointsLedgerEntry entry = new PointsLedgerEntry();
        entry.setUserId(userId);
        entry.setDelta(refundPaid);
        entry.setReason(PointsLedgerEntry.LedgerReason.FEATURE_REFUND);
        entry.setRefKey(featureKey);
        entry.setRefId(refId);
        entry.setBalancePaidAfter(nonNegative(balance.getPaidPoints()));
        entry.setFreeUsed(-freeRestored);
        entry.setFreeRemainingAfter(freeRemainingAfter);
        entry.setMemo(reason == null || reason.isBlank() ? "功能失败自动退还" : reason);
        ledgerRepository.save(entry);
    }

    public PointsDto.BalanceResponse credit(
            Long userId,
            int points,
            PointsLedgerEntry.LedgerReason reason,
            String refKey,
            String refId,
            String memo) {
        return withUserPointLock(userId, () -> inTransaction(
                () -> creditInTransaction(userId, points, reason, refKey, refId, memo)));
    }

    private PointsDto.BalanceResponse creditInTransaction(
            Long userId,
            int points,
            PointsLedgerEntry.LedgerReason reason,
            String refKey,
            String refId,
            String memo) {
        if (points <= 0) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "积分必须大于 0");
        }
        PointsLedgerEntry.LedgerReason ledgerReason = reason == null
                ? PointsLedgerEntry.LedgerReason.ADMIN_CREDIT
                : reason;
        // Admin credits may intentionally use refId=null: MySQL UNIQUE allows
        // repeated NULL refs, while feature debit/refund requires non-null ids.
        UserBalance balance = getOrCreateLockedBalance(userId);
        balance.setPaidPoints(nonNegative(balance.getPaidPoints()) + points);
        balance = userBalanceRepository.save(balance);

        PointsLedgerEntry entry = new PointsLedgerEntry();
        entry.setUserId(userId);
        entry.setDelta(points);
        entry.setReason(ledgerReason);
        entry.setRefKey(refKey);
        entry.setRefId(refId);
        entry.setBalancePaidAfter(nonNegative(balance.getPaidPoints()));
        entry.setFreeUsed(0);
        entry.setMemo(memo);
        ledgerRepository.save(entry);

        return getBalanceInTransaction(userId);
    }

    public PointsDto.BalanceResponse adminDebit(Long userId, int points, String memo) {
        return withUserPointLock(userId, () -> inTransaction(
                () -> adminDebitInTransaction(userId, points, memo)));
    }

    private PointsDto.BalanceResponse adminDebitInTransaction(Long userId, int points, String memo) {
        if (points <= 0) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "积分必须大于 0");
        }
        UserBalance balance = getOrCreateLockedBalance(userId);
        int currentPaid = nonNegative(balance.getPaidPoints());
        if (points > currentPaid) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "积分不足，无法扣减");
        }

        balance.setPaidPoints(currentPaid - points);
        balance = userBalanceRepository.save(balance);

        PointsLedgerEntry entry = new PointsLedgerEntry();
        entry.setUserId(userId);
        entry.setDelta(-points);
        entry.setReason(PointsLedgerEntry.LedgerReason.ADMIN_DEBIT);
        entry.setRefKey("admin_adjustment");
        entry.setRefId(null);
        entry.setBalancePaidAfter(nonNegative(balance.getPaidPoints()));
        entry.setFreeUsed(0);
        entry.setMemo(memo);
        ledgerRepository.save(entry);

        return getBalanceInTransaction(userId);
    }

    @Transactional(readOnly = true)
    public PointsDto.CostPreviewResponse previewCost(Long userId, String featureKey) {
        FeatureCost feature = requireEnabledFeature(featureKey);
        UserBalance balance = userBalanceRepository.findByUserId(userId)
                .orElseGet(() -> new UserBalance(userId, 0, null));
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int usedToday = usageRepository.findByUserIdAndFeatureKeyAndUsageDate(userId, featureKey, today)
                .map(DailyFeatureUsage::getCount)
                .map(PointsService::nonNegative)
                .orElse(0);
        int freeRemaining = Math.max(0, nonNegative(feature.getFreeDailyQuota()) - usedToday);
        boolean willUseFree = freeRemaining > 0;
        int paidPoints = nonNegative(balance.getPaidPoints());
        int paidRemainingAfter = willUseFree ? paidPoints : paidPoints - nonNegative(feature.getCostPoints());
        return new PointsDto.CostPreviewResponse(
                featureKey,
                nonNegative(feature.getCostPoints()),
                freeRemaining,
                willUseFree,
                paidPoints,
                Math.max(0, paidRemainingAfter),
                willUseFree || paidRemainingAfter >= 0
        );
    }

    public PointsDto.BalanceResponse getBalance(Long userId) {
        return withUserPointLock(userId, () -> inTransaction(() -> getBalanceInTransaction(userId)));
    }

    private PointsDto.BalanceResponse getBalanceInTransaction(Long userId) {
        UserBalance balance = getOrCreateBalance(userId);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Map<String, Integer> freeRemaining = new LinkedHashMap<>();
        List<DailyFeatureUsage> todayUsage = usageRepository.findByUserIdAndUsageDate(userId, today);
        Map<String, Integer> usedByFeature = new LinkedHashMap<>();
        todayUsage.forEach(usage -> usedByFeature.put(usage.getFeatureKey(), nonNegative(usage.getCount())));
        featureCostRepository.findAll().stream()
                .filter(feature -> Boolean.TRUE.equals(feature.getEnabled()))
                .forEach(feature -> freeRemaining.put(
                        feature.getFeatureKey(),
                        Math.max(0, nonNegative(feature.getFreeDailyQuota())
                                - usedByFeature.getOrDefault(feature.getFeatureKey(), 0))));
        return new PointsDto.BalanceResponse(freeRemaining, nonNegative(balance.getPaidPoints()));
    }

    @Transactional(readOnly = true)
    public List<PointsDto.LedgerEntryResponse> getLedger(Long userId, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safePage = Math.max(0, offset / safeLimit);
        return ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(safePage, safeLimit))
                .stream()
                .map(PointsDto.LedgerEntryResponse::fromEntity)
                .toList();
    }

    private FeatureCost requireEnabledFeature(String featureKey) {
        FeatureCost feature = featureCostRepository.findByFeatureKey(featureKey)
                .orElseThrow(() -> new PointsException(HttpStatus.NOT_FOUND, "功能计费配置不存在"));
        if (!Boolean.TRUE.equals(feature.getEnabled())) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "功能暂未启用");
        }
        return feature;
    }

    private UserBalance getOrCreateBalance(Long userId) {
        return userBalanceRepository.findByUserId(userId)
                .orElseGet(() -> userBalanceRepository.save(new UserBalance(userId, 0, null)));
    }

    private UserBalance getOrCreateLockedBalance(Long userId) {
        return userBalanceRepository.findLockedByUserId(userId)
                .orElseGet(() -> userBalanceRepository.saveAndFlush(new UserBalance(userId, 0, null)));
    }

    private DailyFeatureUsage getOrCreateLockedUsage(Long userId, String featureKey, LocalDate usageDate) {
        return usageRepository.findLockedByUserIdAndFeatureKeyAndUsageDate(userId, featureKey, usageDate)
                .orElseGet(() -> usageRepository.saveAndFlush(
                        new DailyFeatureUsage(userId, featureKey, usageDate, 0)));
    }

    private static void requireLedgerRefId(String refId, String message) {
        if (refId == null || refId.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private <T> T withUserPointLock(Long userId, Supplier<T> action) {
        synchronized (lockForUser(userId)) {
            return action.get();
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private Object lockForUser(Long userId) {
        Objects.requireNonNull(userId, "userId");
        return userPointLocks[Math.floorMod(userId.hashCode(), userPointLocks.length)];
    }

    private static Object[] createUserPointLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
