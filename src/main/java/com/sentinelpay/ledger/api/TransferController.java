package com.sentinelpay.ledger.api;

import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.domain.TransferRequest;
import com.sentinelpay.ledger.domain.TransferResult;
import com.sentinelpay.ledger.service.LedgerTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private final LedgerTransferService transferService;

    public TransferController(LedgerTransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequestDto request) {
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
