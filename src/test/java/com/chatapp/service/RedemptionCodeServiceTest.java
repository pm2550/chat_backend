package com.chatapp.service;

import com.chatapp.entity.User;
import com.chatapp.exception.CodeAlreadyRedeemedException;
import com.chatapp.exception.CodeExpiredException;
import com.chatapp.exception.CodeNotFoundException;
import com.chatapp.repository.RedemptionCodeRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.CloudStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashSet;
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
@DisplayName("Redemption code service")
class RedemptionCodeServiceTest {

    @Autowired private RedemptionCodeService redemptionCodeService;
    @Autowired private RedemptionCodeRepository codeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PointsService pointsService;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private Long adminId;
    private Long userId;

    @BeforeEach
    void setUp() {
        adminId = createUser("admin", User.Role.ADMIN);
        userId = createUser("redeemer", User.Role.USER);
    }

    @Test
    @DisplayName("issueBatch returns unique plaintext codes and stores only hashes")
    void issueBatch_returnsPlaintextAndStoresHashes() {
        var codes = redemptionCodeService.issueBatch(5, 100, "batch-a",
                null, "memo", adminId);

        assertEquals(5, codes.size());
        assertEquals(5, new HashSet<>(codes).size());
        for (String code : codes) {
            assertTrue(code.matches("[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}"));
            assertFalse(codeRepository.existsById(code));
            assertTrue(codeRepository.existsById(RedemptionCodeService.hashCode(code)));
        }
    }

    @Test
    @DisplayName("issueBatch with same batch label adds more codes")
    void issueBatch_sameLabelAddsMore() {
        redemptionCodeService.issueBatch(2, 10, "batch-b", null, null, adminId);
        redemptionCodeService.issueBatch(3, 10, "batch-b", null, null, adminId);

        assertEquals(5, redemptionCodeService.listCodes("unused", "batch-b", 10).size());
    }

    @Test
    @DisplayName("redeem valid code credits points and marks redeemed")
    void redeem_validCodeCreditsPoints() {
        String code = redemptionCodeService.issueBatch(1, 55, "batch-c",
                null, "paid cash", adminId).get(0);

        var result = redemptionCodeService.redeem(userId, code);

        assertEquals(55, result.getCredited());
        assertEquals(55, result.getNewPaidBalance());
        assertEquals(55, pointsService.getBalance(userId).getPaidPoints());
        assertNotNull(codeRepository.findByCodeHash(RedemptionCodeService.hashCode(code))
                .orElseThrow().getRedeemedAt());
    }

    @Test
    @DisplayName("redeem already used code throws")
    void redeem_alreadyUsedThrows() {
        String code = redemptionCodeService.issueBatch(1, 20, "batch-d",
                null, null, adminId).get(0);
        redemptionCodeService.redeem(userId, code);

        assertThrows(CodeAlreadyRedeemedException.class,
                () -> redemptionCodeService.redeem(createUser("other", User.Role.USER), code));
    }

    @Test
    @DisplayName("redeem expired code throws")
    void redeem_expiredThrows() {
        String code = redemptionCodeService.issueBatch(1, 20, "batch-e",
                LocalDateTime.now().minusMinutes(1), null, adminId).get(0);

        assertThrows(CodeExpiredException.class,
                () -> redemptionCodeService.redeem(userId, code));
    }

    @Test
    @DisplayName("redeem unknown code throws")
    void redeem_unknownThrows() {
        assertThrows(CodeNotFoundException.class,
                () -> redemptionCodeService.redeem(userId, "ABCD-EFGH-2345"));
    }

    @Test
    @DisplayName("redeem is case-insensitive")
    void redeem_caseInsensitive() {
        String code = redemptionCodeService.issueBatch(1, 15, "batch-f",
                null, null, adminId).get(0);

        var result = redemptionCodeService.redeem(userId, code.toLowerCase());

        assertEquals(15, result.getCredited());
    }

    private Long createUser(String prefix, User.Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(prefix + "_" + suffix);
        user.setPassword("not-used");
        user.setEmail(prefix + "_" + suffix + "@test.com");
        user.setDisplayName(prefix);
        user.setIsActive(true);
        user.getRoles().add(role);
        return userRepository.save(user).getId();
    }
}
