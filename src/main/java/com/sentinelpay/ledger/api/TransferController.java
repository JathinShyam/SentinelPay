package com.sentinelpay.ledger.api;

import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.ledger.domain.TransferRequest;
import com.sentinelpay.ledger.domain.TransferResult;
import com.sentinelpay.ledger.service.AccountAccessService;
import com.sentinelpay.ledger.service.LedgerTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private final LedgerTransferService transferService;
    private final AccountAccessService accountAccess;

    public TransferController(LedgerTransferService transferService,
                              AccountAccessService accountAccess) {
        this.transferService = transferService;
        this.accountAccess = accountAccess;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequestDto request) {
        accountAccess.requireVisible(request.sourceAccountId(), user);
        TransferResult result = transferService.executeTransfer(new TransferRequest(
                idempotencyKey,
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amountMinor(),
                request.currency(),
                request.description()));
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(TransferResponse.from(result));
    }
}
