package com.chatapp.service;

import com.chatapp.entity.DailyFeatureUsage;
import com.chatapp.entity.FeatureCost;
import com.chatapp.entity.PointsLedgerEntry;
import com.chatapp.entity.User;
import com.chatapp.entity.UserBalance;
import com.chatapp.exception.InsufficientPointsException;
import com.chatapp.repository.DailyFeatureUsageRepository;
import com.chatapp.repository.FeatureCostRepository;
import com.chatapp.repository.PointsLedgerRepository;
import com.chatapp.repository.UserBalanceRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@Import(com.chatapp.integration.TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Points service concurrency on MySQL")
class PointsServiceConcurrencyTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("chatapp_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @Autowired private PointsService pointsService;
    @Autowired private UserRepository userRepository;
    @Autowired private FeatureCostRepository featureCostRepository;
    @Autowired private UserBalanceRepository userBalanceRepository;
    @Autowired private DailyFeatureUsageRepository usageRepository;
    @Autowired private PointsLedgerRepository ledgerRepository;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    @Test
    @DisplayName("concurrent debits never oversubscribe paid balance")
    void concurrentDebits_neverOversubscribeBalance() throws Exception {
        Long userId = createUser("paid_race").getId();
        String featureKey = "paid_race_" + UUID.randomUUID().toString().substring(0, 8);
        featureCostRepository.saveAndFlush(new FeatureCost(featureKey, 3, 0, true, "paid race", null));
        userBalanceRepository.saveAndFlush(new UserBalance(userId, 10, null));
        usageRepository.saveAndFlush(new DailyFeatureUsage(
                userId,
                featureKey,
                LocalDate.now(ZoneId.of("Asia/Shanghai")),
                0));

        List<DebitOutcome> outcomes = runConcurrentDebits(userId, featureKey, 10);
        long successCount = outcomes.stream().filter(DebitOutcome::successful).count();
        long insufficientCount = outcomes.stream()
                .filter(outcome -> outcome.error() instanceof InsufficientPointsException)
                .count();

        assertEquals(3, successCount);
        assertEquals(7, insufficientCount);
        assertEquals(1, userBalanceRepository.findById(userId).orElseThrow().getPaidPoints());
        assertEquals(3, countDebits(userId, featureKey));
    }

    @Test
    @DisplayName("concurrent first-ever debit does not corrupt balance")
    void concurrentFirstEverDebit_doesNotCorruptBalance() throws Exception {
        Long userId = createUser("fresh_race").getId();
        String featureKey = "fresh_race_" + UUID.randomUUID().toString().substring(0, 8);
        featureCostRepository.saveAndFlush(new FeatureCost(featureKey, 0, 2, true, "fresh race", null));

        List<DebitOutcome> outcomes = runConcurrentDebits(userId, featureKey, 2);
        long successCount = outcomes.stream().filter(DebitOutcome::successful).count();

        assertEquals(2, successCount, "first-ever free quota debits should both complete");
        UserBalance balance = userBalanceRepository.findById(userId).orElse(null);
        assertNotNull(balance);
        assertEquals(0, balance.getPaidPoints());
        assertEquals(2, usageRepository.findByUserIdAndFeatureKeyAndUsageDate(
                userId,
                featureKey,
                LocalDate.now(ZoneId.of("Asia/Shanghai"))).orElseThrow().getCount());
        assertEquals(2, countDebits(userId, featureKey));
    }

    private List<DebitOutcome> runConcurrentDebits(Long userId, String featureKey, int count) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<DebitOutcome>>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    pointsService.debit(userId, featureKey, "race-" + index);
                    return DebitOutcome.ok();
                } catch (Exception ex) {
                    return DebitOutcome.failure(ex);
                }
            }));
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        var outcomes = new ArrayList<DebitOutcome>();
        for (var future : futures) {
            outcomes.add(future.get());
        }
        return outcomes;
    }

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(prefix + "_" + suffix);
        user.setPassword("not-used");
        user.setEmail(prefix + "_" + suffix + "@test.com");
        user.setDisplayName("Race User");
        user.setIsActive(true);
        user.getRoles().add(User.Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private long countDebits(Long userId, String featureKey) {
        return ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20))
                .stream()
                .filter(entry -> entry.getReason() == PointsLedgerEntry.LedgerReason.FEATURE_DEBIT)
                .filter(entry -> featureKey.equals(entry.getRefKey()))
                .count();
    }

    private record DebitOutcome(boolean successful, Exception error) {
        static DebitOutcome ok() {
            return new DebitOutcome(true, null);
        }

        static DebitOutcome failure(Exception error) {
            return new DebitOutcome(false, error);
        }
    }
}
