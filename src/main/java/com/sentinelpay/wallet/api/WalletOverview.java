package com.sentinelpay.wallet.api;

import com.sentinelpay.auth.api.UserView;
import com.sentinelpay.ledger.domain.AccountView;

import java.time.Instant;
import java.util.List;

/**
 * Everything the wallet home needs in one read. Totals and proof are computed
 * by the server in integer minor units so the browser never does money math.
 */
public record WalletOverview(
        UserView user,
        List<CurrencyTotal> totals,
        List<AccountView> accounts,
        List<ActivityItem> recentActivity,
        ProofSummary proof
) {
    public record CurrencyTotal(String currency, long balanceMinor) {
    }

    public record ProofSummary(
            boolean balanced,
            int accountsChecked,
            long entryCount,
            long driftMinor,
            Instant checkedAt
    ) {
    }
}
