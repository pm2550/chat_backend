package com.chatapp.controller;

import com.chatapp.dto.PointsDto;
import com.chatapp.entity.PointsLedgerEntry;
import com.chatapp.service.PointsService;
import com.chatapp.service.RedemptionCodeService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCodeController {

    private final RedemptionCodeService redemptionCodeService;
    private final PointsService pointsService;
    private final UserService userService;

    @PostMapping("/codes/issue")
    public ResponseEntity<PointsDto.IssueCodesResponse> issueCodes(
            @Valid @RequestBody PointsDto.IssueCodesRequest request,
            Authentication auth) {
        Long adminUserId = userService.findUserByUsername(auth.getName()).getId();
        List<String> codes = redemptionCodeService.issueBatch(
                request.getCount(),
                request.getPointsEach(),
                request.getBatchLabel(),
                request.getExpiresAt(),
                request.getMemo(),
                adminUserId);
        return ResponseEntity.ok(new PointsDto.IssueCodesResponse(codes));
    }

    @GetMapping("/codes")
    public ResponseEntity<List<PointsDto.CodeMetadataResponse>> listCodes(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(name = "batch_label", required = false) String batchLabel,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(redemptionCodeService.listCodes(status, batchLabel, limit));
    }

    @PostMapping("/users/{userId}/credit")
    public ResponseEntity<PointsDto.BalanceResponse> creditUser(
            @PathVariable Long userId,
            @Valid @RequestBody PointsDto.AdminCreditRequest request) {
        return ResponseEntity.ok(pointsService.credit(
                userId,
                request.getPoints(),
                PointsLedgerEntry.LedgerReason.ADMIN_CREDIT,
                "admin_adjustment",
                null,
                request.getMemo()));
    }

    @GetMapping("/users/{userId}/ledger")
    public ResponseEntity<List<PointsDto.LedgerEntryResponse>> userLedger(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(pointsService.getLedger(userId, limit, offset));
    }
}
