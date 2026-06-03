package com.chatapp.service;

import com.chatapp.dto.PointsDto;
import com.chatapp.entity.DailyFeatureUsage;
import com.chatapp.entity.FeatureCost;
import com.chatapp.entity.PointsLedgerEntry;
import com.chatapp.entity.User;
import com.chatapp.exception.InsufficientPointsException;
import com.chatapp.repository.DailyFeatureUsageRepository;
import com.chatapp.repository.FeatureCostRepository;
import com.chatapp.repository.PointsLedgerRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.CloudStorageService;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@ActiveProfiles("test")
@Import(com.chatapp.integration.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Points service")
class PointsServiceTest {

    @Autowired private PointsService pointsService;
    @Autowired private UserRepository userRepository;
    @Autowired private FeatureCostRepository featureCostRepository;
    @Autowired private DailyFeatureUsageRepository usageRepository;
    @Autowired private PointsLedgerRepository ledgerRepository;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private Long userId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("points_" + suffix);
        user.setPassword("not-used");
        user.setEmail("points_" + suffix + "@test.com");
        user.setDisplayName("Points User");
        user.setIsActive(true);
        user.getRoles().add(User.Role.USER);
        userId = userRepository.save(user).getId();

        featureCostRepository.save(new FeatureCost(
                "test_debit",
                1,
                10,
                true,
                "test",
                null));
    }

    @Test
    @DisplayName("debit uses free quota first and does not touch paid points")
    void debit_usesFreeQuotaFirst() {
        PointsDto.DebitResult result = pointsService.debit(userId, "test_debit", "free-1");

        assertEquals(1, result.getUsedFree());
        assertEquals(0, result.getUsedPaid());
        assertEquals(0, result.getBalancePaidAfter());
        var balance = pointsService.getBalance(userId);
        assertEquals(9, balance.getFreeRemainingPerFeature().get("test_debit"));
        assertEquals(0, balance.getPaidPoints());
    }

    @Test
    @DisplayName("debit uses paid balance after daily free quota is exhausted")
    void debit_usesPaidAfterFreeExhausted() {
        pointsService.credit(userId, 5, PointsLedgerEntry.LedgerReason.ADMIN_CREDIT,
                "test", "credit-1", "seed");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        usageRepository.save(new DailyFeatureUsage(userId, "test_debit", today, 10));

        PointsDto.DebitResult result = pointsService.debit(userId, "test_debit", "paid-1");

        assertEquals(0, result.getUsedFree());
        assertEquals(1, result.getUsedPaid());
        assertEquals(4, result.getBalancePaidAfter());
    }

    @Test
    @DisplayName("debit fails when free quota and paid points are both insufficient")
    void debit_failsWhenInsufficient() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        usageRepository.save(new DailyFeatureUsage(userId, "test_debit", today, 10));

        assertThrows(InsufficientPointsException.class,
                () -> pointsService.debit(userId, "test_debit", "nope"));
    }

    @Test
    @DisplayName("debit requires a non-blank ref id for idempotent ledger rows")
    void debit_requiresRefId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> pointsService.debit(userId, "test_debit", null));

        assertTrue(ex.getMessage().contains("refId"));
    }

    @Test
    @DisplayName("ledger entity mirrors DB unique constraint")
    void ledgerEntity_mirrorsUniqueConstraint() {
        Table table = PointsLedgerEntry.class.getAnnotation(Table.class);

        assertNotNull(table);
        assertTrue(
                Arrays.stream(table.uniqueConstraints()).anyMatch(this::isLedgerRefConstraint),
                "PointsLedgerEntry must mirror uk_ledger_user_reason_ref");
    }

    @Test
    @DisplayName("refund restores paid points and is idempotent on ref id")
    void refund_isIdempotent() {
        pointsService.credit(userId, 2, PointsLedgerEntry.LedgerReason.ADMIN_CREDIT,
                "test", "credit-2", "seed");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        usageRepository.save(new DailyFeatureUsage(userId, "test_debit", today, 10));
        pointsService.debit(userId, "test_debit", "refund-paid");

        pointsService.refund(userId, "test_debit", "refund-paid", "failed");
        pointsService.refund(userId, "test_debit", "refund-paid", "failed");

        assertEquals(2, pointsService.getBalance(userId).getPaidPoints());
        long refundRows = ledgerRepository.findByUserIdAndRefKeyAndRefIdOrderByCreatedAtDesc(
                        userId, "test_debit", "refund-paid")
                .stream()
                .filter(row -> row.getReason() == PointsLedgerEntry.LedgerReason.FEATURE_REFUND)
                .count();
        assertEquals(1, refundRows);
    }

    @Test
    @DisplayName("credit writes a positive ledger row")
    void credit_writesLedger() {
        pointsService.credit(userId, 12, PointsLedgerEntry.LedgerReason.ADMIN_CREDIT,
                "admin", "manual", "manual add");

        assertEquals(12, pointsService.getBalance(userId).getPaidPoints());
        assertTrue(ledgerRepository.findByUserIdAndRefKeyAndRefIdOrderByCreatedAtDesc(
                        userId, "admin", "manual")
                .stream()
                .anyMatch(row -> row.getDelta() == 12
                        && row.getReason() == PointsLedgerEntry.LedgerReason.ADMIN_CREDIT));
    }

    @Test
    @DisplayName("preview reports free use and paid projection")
    void preview_reportsProjection() {
        PointsDto.CostPreviewResponse first = pointsService.previewCost(userId, "test_debit");
        assertTrue(first.isSufficient());
        assertTrue(first.isWillUseFree());
        assertEquals(10, first.getFreeRemaining());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        usageRepository.save(new DailyFeatureUsage(userId, "test_debit", today, 10));
        PointsDto.CostPreviewResponse exhausted = pointsService.previewCost(userId, "test_debit");
        assertFalse(exhausted.isSufficient());
        assertFalse(exhausted.isWillUseFree());
    }

    @Test
    @DisplayName("yesterday's usage does not reduce today's free quota")
    void dailyUsage_isDateScoped() {
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        usageRepository.save(new DailyFeatureUsage(userId, "test_debit", yesterday, 10));

        assertEquals(10, pointsService.getBalance(userId)
                .getFreeRemainingPerFeature().get("test_debit"));
    }

    private boolean isLedgerRefConstraint(UniqueConstraint constraint) {
        return "uk_ledger_user_reason_ref".equals(constraint.name())
                && Arrays.asList(constraint.columnNames()).containsAll(
                        List.of("user_id", "reason", "ref_key", "ref_id"));
    }
}
