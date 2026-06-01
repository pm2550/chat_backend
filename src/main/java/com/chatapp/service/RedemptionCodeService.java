package com.chatapp.service;

import com.chatapp.dto.PointsDto;
import com.chatapp.entity.PointsLedgerEntry;
import com.chatapp.entity.RedemptionCode;
import com.chatapp.exception.CodeAlreadyRedeemedException;
import com.chatapp.exception.CodeExpiredException;
import com.chatapp.exception.CodeNotFoundException;
import com.chatapp.exception.PointsException;
import com.chatapp.repository.RedemptionCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedemptionCodeService {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}$");

    private final RedemptionCodeRepository codeRepository;
    private final PointsService pointsService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public List<String> issueBatch(
            int count,
            int pointsEach,
            String batchLabel,
            LocalDateTime expiresAt,
            String memo,
            Long issuedByUserId) {
        if (count < 1 || count > 500) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "兑换码数量必须在 1 到 500 之间");
        }
        if (pointsEach <= 0) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "积分必须大于 0");
        }
        List<String> plainCodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code;
            String hash;
            do {
                code = generateCode();
                hash = hashCode(code);
            } while (codeRepository.existsById(hash));

            RedemptionCode entity = new RedemptionCode();
            entity.setCodeHash(hash);
            entity.setPoints(pointsEach);
            entity.setBatchLabel(batchLabel);
            entity.setMemo(memo);
            entity.setExpiresAt(expiresAt);
            entity.setIssuedByUserId(issuedByUserId);
            codeRepository.save(entity);
            plainCodes.add(code);
        }
        log.info("issued {} redemption codes for batch {}", count, batchLabel);
        return plainCodes;
    }

    @Transactional
    public PointsDto.RedeemResponse redeem(Long userId, String userFacingCode) {
        String normalized = normalizeCode(userFacingCode);
        String hash = hashCode(normalized);
        RedemptionCode code = codeRepository.findLockedByCodeHash(hash)
                .orElseThrow(CodeNotFoundException::new);
        if (code.getRedeemedAt() != null) {
            throw new CodeAlreadyRedeemedException();
        }
        LocalDateTime now = LocalDateTime.now();
        if (code.getExpiresAt() != null && !code.getExpiresAt().isAfter(now)) {
            throw new CodeExpiredException();
        }
        code.setRedeemedByUserId(userId);
        code.setRedeemedAt(now);
        codeRepository.save(code);

        PointsDto.BalanceResponse balance = pointsService.credit(
                userId,
                code.getPoints(),
                PointsLedgerEntry.LedgerReason.REDEEM_CODE,
                "redemption_code",
                code.getCodeHash(),
                code.getMemo());
        return new PointsDto.RedeemResponse(code.getPoints(), balance.getPaidPoints(), code.getMemo());
    }

    @Transactional(readOnly = true)
    public List<PointsDto.CodeMetadataResponse> listCodes(String status, String batchLabel, int limit) {
        String safeStatus = status == null || status.isBlank()
                ? "all"
                : status.toLowerCase(Locale.ROOT);
        if (!List.of("all", "unused", "redeemed", "expired").contains(safeStatus)) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "不支持的兑换码状态");
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return codeRepository.searchCodes(safeStatus, blankToNull(batchLabel), LocalDateTime.now(), PageRequest.of(0, safeLimit))
                .stream()
                .map(PointsDto.CodeMetadataResponse::fromEntity)
                .toList();
    }

    public static String normalizeCode(String userFacingCode) {
        if (userFacingCode == null) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "兑换码格式错误");
        }
        String normalized = userFacingCode.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new PointsException(HttpStatus.BAD_REQUEST, "兑换码格式错误");
        }
        return normalized;
    }

    public static String hashCode(String normalizedCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    normalizedCode.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String generateCode() {
        StringBuilder raw = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            raw.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
