package com.sentinelpay.wallet.service;

import com.sentinelpay.auth.api.UserView;
import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.common.exception.ApiErrorCode;
import com.sentinelpay.common.exception.ApiException;
import com.sentinelpay.common.exception.TransactionNotFoundException;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.domain.LedgerEntry;
import com.sentinelpay.ledger.domain.TransactionEntity;
import com.sentinelpay.ledger.repository.LedgerEntryRepository;
import com.sentinelpay.ledger.repository.LedgerEntryRepository.AccountActivityRow;
import com.sentinelpay.ledger.repository.LedgerEntryRepository.OwnerLedgerSummary;
import com.sentinelpay.ledger.repository.TransactionRepository;
import com.sentinelpay.ledger.service.AccountAccessService;
import com.sentinelpay.ledger.service.AccountService;
import com.sentinelpay.wallet.api.ActivityItem;
import com.sentinelpay.wallet.api.ActivityPage;
import com.sentinelpay.wallet.api.TransactionDetailView;
import com.sentinelpay.wallet.api.WalletOverview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only customer views over the ledger. Nothing here mutates money or
 * recalculates a balance: balances come from the materialised account row and
 * the per-entry running balance written by the posting transaction.
 */
@Service
public class WalletService {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int OVERVIEW_ACTIVITY_SIZE = 6;

    private final AccountService accountService;
    private final AccountAccessService accountAccess;
    private final LedgerEntryRepository ledgerEntries;
    private final TransactionRepository transactions;

    public WalletService(AccountService accountService,
                         AccountAccessService accountAccess,
                         LedgerEntryRepository ledgerEntries,
                         TransactionRepository transactions) {
        this.accountService = accountService;
        this.accountAccess = accountAccess;
        this.ledgerEntries = ledgerEntries;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public WalletOverview overview(AuthenticatedUser user) {
        String ownerId = user.userId().toString();
        List<AccountView> accounts = accountService.getAccountsByOwner(ownerId);

        Map<String, Long> totals = new LinkedHashMap<>();
        accounts.stream()
                .sorted(Comparator.comparing(AccountView::currency))
                .forEach(account -> totals.merge(account.currency(),
                        account.balanceMinor(), Long::sum));

        List<ActivityItem> recent = ledgerEntries
                .findRecentActivityForOwner(ownerId, OVERVIEW_ACTIVITY_SIZE)
                .stream()
                .map(ActivityItem::from)
                .toList();

        OwnerLedgerSummary summary = ledgerEntries.summarizeOwner(ownerId);
        WalletOverview.ProofSummary proof = new WalletOverview.ProofSummary(
                summary.driftMinor() == 0,
                summary.accountsChecked(),
                summary.entryCount(),
                summary.driftMinor(),
                Instant.now());

        return new WalletOverview(UserView.from(user), totals.entrySet().stream()
                .map(entry -> new WalletOverview.CurrencyTotal(entry.getKey(), entry.getValue()))
                .toList(), accounts, recent, proof);
    }

    @Transactional(readOnly = true)
    public ActivityPage activity(UUID accountId, Integer limit, String cursor,
                                 AuthenticatedUser user) {
        accountAccess.requireVisible(accountId, user);
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_PAGE_SIZE);
        }

        Cursor decoded = cursor == null || cursor.isBlank() ? null : decode(cursor);
        List<AccountActivityRow> rows = decoded == null
                ? ledgerEntries.findActivity(accountId, null, null, pageSize + 1)
                : ledgerEntries.findActivity(accountId, decoded.createdAt(),
                        decoded.entryId(), pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<AccountActivityRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            AccountActivityRow last = page.getLast();
            nextCursor = encode(last.createdAt(), last.entryId());
        }
        return new ActivityPage(page.stream().map(ActivityItem::from).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public TransactionDetailView transactionDetail(UUID transactionId, AuthenticatedUser user) {
        TransactionEntity transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        List<LedgerEntry> entries = ledgerEntries.findByTransactionId(transactionId);

        if (!user.isAdmin() && entries.stream()
                .map(LedgerEntry::accountId)
                .distinct()
                .noneMatch(accountId -> accountAccess.isVisible(accountId, user))) {
            throw new TransactionNotFoundException(transactionId);
        }

        UUID reversedBy = transactions.findReversalOf(transactionId)
                .map(TransactionEntity::transactionId)
                .orElse(null);

        List<TransactionDetailView.EntryView> entryViews = entries.stream()
                .map(entry -> new TransactionDetailView.EntryView(
                        entry.entryId(),
                        entry.accountId(),
                        entry.direction().name(),
                        Math.abs(entry.signedAmountMinor()),
                        canSeeBalance(entry.accountId(), user) ? entry.runningBalanceMinor() : null,
                        entry.createdAt()))
                .toList();

        return new TransactionDetailView(transaction.transactionId(), transaction.txType().name(),
                transaction.status().name(), transaction.currency(), transaction.description(),
                transaction.reversalOf(), reversedBy, transaction.createdAt(), entryViews);
    }

    private boolean canSeeBalance(UUID accountId, AuthenticatedUser user) {
        return accountAccess.isVisible(accountId, user);
    }

    private static String encode(Instant createdAt, UUID entryId) {
        String raw = createdAt + "|" + entryId;
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        try {
            String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new Cursor(Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST, "cursor is not valid");
        }
    }

    private record Cursor(Instant createdAt, UUID entryId) {
    }
}
