package com.sentinelpay.ledger.service;

import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.domain.AccountStatus;
import com.sentinelpay.ledger.domain.AccountType;
import com.sentinelpay.ledger.repository.AccountRepository;
import com.sentinelpay.ledger.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class SystemClearingSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemClearingSeeder.class);
    private static final List<String> SUPPORTED_CURRENCIES = List.of("INR", "USD", "EUR");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerTransferService transferService;

    public SystemClearingSeeder(AccountRepository accountRepository,
                                TransactionRepository transactionRepository,
                                LedgerTransferService transferService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transferService = transferService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String currency : SUPPORTED_CURRENCIES) {
            seedCurrency(currency);
        }
    }

    private void seedCurrency(String currency) {
        UUID equityId = SystemAccounts.equity(currency);
        UUID reserveId = SystemAccounts.reserve(currency);
        Instant now = Instant.now();

        accountRepository.findById(equityId).orElseGet(() -> {
            Account account = new Account(equityId, "SYSTEM_EQUITY", currency, 0L,
                    AccountType.SYSTEM, AccountStatus.ACTIVE, 0L, now, now);
            accountRepository.insert(account);
            return account;
        });

        accountRepository.findById(reserveId).orElseGet(() -> {
            Account account = new Account(reserveId, "SYSTEM_RESERVE", currency, 0L,
                    AccountType.SYSTEM, AccountStatus.ACTIVE, 0L, now, now);
            accountRepository.insert(account);
            return account;
        });

        String genesisKey = SystemAccounts.genesisIdempotencyKey(currency);
        if (transactionRepository.findByIdempotencyKey(genesisKey).isEmpty()) {
            transferService.executeSystemDeposit(
                    equityId,
                    reserveId,
                    SystemAccounts.GENESIS_AMOUNT_MINOR,
                    currency,
                    genesisKey,
                    "GENESIS: system reserve funding");
            log.info("Seeded {} genesis reserve with {} minor units", currency,
                    SystemAccounts.GENESIS_AMOUNT_MINOR);
        }
    }
}
