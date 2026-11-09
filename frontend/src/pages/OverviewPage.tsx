import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getOverview } from '../api/client';
import type { Account } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { PageHeader } from '../components/PageHeader';
import { LedgerTable } from '../components/LedgerTable';
import { OpenAccountDialog } from '../components/OpenAccountDialog';
import { TransactionDetailDialog } from '../components/TransactionDetailDialog';
import {
  EmptyState,
  ErrorNotice,
  LoadingBlock,
  Seal,
  StatusTag,
} from '../components/ui';
import { AlertIcon, CheckIcon, ChevronRightIcon, PlusIcon } from '../components/icons';
import { formatMinor, shortId, todayLabel } from '../format';

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) {
    return 'Good morning';
  }
  if (hour < 18) {
    return 'Good afternoon';
  }
  return 'Good evening';
}

function ordered(accounts: Account[]): Account[] {
  return [...accounts].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

export function OverviewPage() {
  const { user } = useAuth();
  const { data, isPending, error, refetch } = useQuery({
    queryKey: ['overview'],
    queryFn: getOverview,
  });
  const [openAccount, setOpenAccount] = useState(false);
  const [selectedTransaction, setSelectedTransaction] = useState<string | null>(null);

  const firstName = user?.displayName.split(/\s+/)[0] ?? 'there';

  if (isPending) {
    return <LoadingBlock label="Reading your accounts…" />;
  }

  if (error || !data) {
    return (
      <>
        <PageHeader folio={`Statement · ${todayLabel()}`} title="Your ledger" />
        <ErrorNotice>
          {error instanceof Error ? error.message : 'The ledger could not be read.'}
          <button type="button" className="text-button" onClick={() => void refetch()}>
            Try again
          </button>
        </ErrorNotice>
      </>
    );
  }

  const accounts = ordered(data.accounts);
  const hasAccounts = accounts.length > 0;
  const balanced = data.proof.balanced;

  return (
    <>
      <PageHeader
        folio={`Statement · ${todayLabel()}`}
        title={`${greeting()}, ${firstName}.`}
        lede="Your balances, postings and proof — all in one ledger."
        actions={
          hasAccounts ? (
            <button type="button" className="btn btn--ghost" onClick={() => setOpenAccount(true)}>
              <PlusIcon width={17} height={17} />
              Open account
            </button>
          ) : undefined
        }
      />

      {hasAccounts ? (
        <>
          <section className="balance-band" aria-label="Balances">
            <div className="balance-band__totals">
              {data.totals.map((total) => (
                <div className="balance-band__item" key={total.currency}>
                  <span className="balance-band__label">{total.currency} balance</span>
                  <span className="balance-band__value money">
                    {formatMinor(total.balanceMinor, total.currency)}
                  </span>
                </div>
              ))}
            </div>
            <Seal
              state={balanced ? 'balanced' : 'attention'}
              label={balanced ? 'Balanced' : 'Attention'}
              detail={balanced ? 'journal verified' : 'drift found'}
            />
          </section>

          <section className="section" aria-labelledby="accounts-title">
            <div className="section-heading">
              <div>
                <h2 id="accounts-title">Accounts</h2>
                <p>Each account has its own currency and statement.</p>
              </div>
              <button type="button" className="text-button" onClick={() => setOpenAccount(true)}>
                <PlusIcon width={16} height={16} />
                New account
              </button>
            </div>

            <div className="folios">
              {accounts.map((account, index) => (
                <article className="folio-card" key={account.accountId}>
                  <div className="folio-card__top">
                    <span className="folio-card__no">
                      Folio {String(index + 1).padStart(2, '0')}
                    </span>
                    <StatusTag status={account.status} />
                  </div>
                  <p className="folio-card__currency">{account.currency}</p>
                  <p className="folio-card__label">Account {index + 1}</p>
                  <p className="folio-card__balance money">
                    {formatMinor(account.balanceMinor, account.currency)}
                  </p>
                  <div className="folio-card__foot">
                    <span className="mono folio-card__id">{shortId(account.accountId)}</span>
                    <Link
                      className="text-link"
                      to={`/wallet/activity?account=${account.accountId}`}
                    >
                      Statement
                      <ChevronRightIcon width={15} height={15} />
                    </Link>
                  </div>
                </article>
              ))}
            </div>
          </section>

          <section className="section" aria-labelledby="recent-title">
            <div className="section-heading">
              <div>
                <h2 id="recent-title">Recent postings</h2>
                <p>The latest entries across all your accounts.</p>
              </div>
              <Link className="text-link" to="/wallet/activity">
                Full statement
                <ChevronRightIcon width={15} height={15} />
              </Link>
            </div>

            {data.recentActivity.length > 0 ? (
              <LedgerTable
                items={data.recentActivity}
                accounts={accounts}
                onSelect={(item) => setSelectedTransaction(item.transactionId)}
                caption="Recent postings"
              />
            ) : (
              <p className="quiet">No postings yet. Money will appear here as soon as it moves.</p>
            )}
          </section>

          <section
            className={`proof-strip${balanced ? '' : ' proof-strip--attention'}`}
            aria-labelledby="proof-strip-title"
          >
            <span className="proof-strip__icon" aria-hidden="true">
              {balanced ? <CheckIcon width={20} height={20} /> : <AlertIcon width={20} height={20} />}
            </span>
            <div className="proof-strip__copy">
              <h2 id="proof-strip-title">
                {balanced ? 'The journal balances' : 'The journal needs attention'}
              </h2>
              <p>
                {balanced
                  ? `${data.proof.accountsChecked} account${
                      data.proof.accountsChecked === 1 ? '' : 's'
                    } and ${data.proof.entryCount} journal entr${
                      data.proof.entryCount === 1 ? 'y' : 'ies'
                    } checked. Stored balances match the ledger.`
                  : `A difference of ${data.proof.driftMinor} minor units was found. Inspect the proof before moving money.`}
              </p>
            </div>
            <Link className="text-link" to="/wallet/proof">
              Inspect proof
              <ChevronRightIcon width={15} height={15} />
            </Link>
          </section>
        </>
      ) : (
        <EmptyState
          title="Open your first account"
          action={
            <button type="button" className="btn btn--primary" onClick={() => setOpenAccount(true)}>
              <PlusIcon width={17} height={17} />
              Open an account
            </button>
          }
        >
          <p>
            A SentinelPay account keeps one currency and a full statement of every
            posting. You can open an INR, USD or EUR account now.
          </p>
        </EmptyState>
      )}

      {openAccount ? <OpenAccountDialog onClose={() => setOpenAccount(false)} /> : null}
      {selectedTransaction ? (
        <TransactionDetailDialog
          transactionId={selectedTransaction}
          accounts={accounts}
          onClose={() => setSelectedTransaction(null)}
        />
      ) : null}
    </>
  );
}
