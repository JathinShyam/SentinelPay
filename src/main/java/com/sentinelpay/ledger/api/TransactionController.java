package com.sentinelpay.ledger.api;

import com.sentinelpay.ledger.domain.ReversalResult;
import com.sentinelpay.ledger.service.ReversalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final ReversalService reversalService;

    public TransactionController(ReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping("/transactions/{transactionId}/reverse")
    public ResponseEntity<ReversalResponse> reverse(
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) ReversalRequestDto request) {
        ReversalResult result = reversalService.reverse(transactionId, idempotencyKey,
                request == null ? null : request.description());
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ReversalResponse.from(result));
    }
}
