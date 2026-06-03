package com.chatapp.controller;

import com.chatapp.dto.PointsDto;
import com.chatapp.service.PointsService;
import com.chatapp.service.RedemptionCodeService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;
    private final RedemptionCodeService redemptionCodeService;
    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<PointsDto.BalanceResponse> me(Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(pointsService.getBalance(userId));
    }

    @GetMapping("/ledger")
    public ResponseEntity<List<PointsDto.LedgerEntryResponse>> ledger(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(pointsService.getLedger(userId, limit, offset));
    }

    @PostMapping("/preview/{featureKey}")
    public ResponseEntity<PointsDto.CostPreviewResponse> preview(
            @PathVariable String featureKey,
            Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(pointsService.previewCost(userId, featureKey));
    }

    @PostMapping("/redeem")
    public ResponseEntity<PointsDto.RedeemResponse> redeem(
            @Valid @RequestBody PointsDto.RedeemRequest request,
            Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(redemptionCodeService.redeem(userId, request.getCode()));
    }

    @PostMapping("/debit-test")
    public ResponseEntity<PointsDto.DebitResult> debitTest(Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(pointsService.debit(
                userId,
                "test_debit",
                "smoke-" + UUID.randomUUID()));
    }

    private Long currentUserId(Authentication auth) {
        return userService.findUserByUsername(auth.getName()).getId();
    }
}
