import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getOverview } from '../api/client';
import type { Account } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { LedgerTable } from '../components/LedgerTable';
import { OpenAccountDialog } from '../components/OpenAccountDialog';
import { TransactionDetailDialog } from '../components/TransactionDetailDialog';
import { accountLabel } from '../components/accounts';
import {
  EmptyState,
  ErrorNotice,
  LoadingBlock,
  StatusMark,
  StatusTag,
} from '../components/ui';
import { PlusIcon } from '../components/icons';
import { formatMinor, shortId, todayLabel } from '../format';

function ordered(accounts: Account[]): Account[] {
  return [...accounts].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

export function OverviewPage() {
  const { data, isPending, error, refetch } = useQuery({
    queryKey: ['overview'],
    queryFn: getOverview,
  });
  const [openAccount, setOpenAccount] = useState(false);
  const [selectedTransaction, setSelectedTransaction] = useState<string | null>(null);

  if (isPending) {
    return <LoadingBlock label="Reading your accounts…" />;
  }

  if (error || !data) {
    return (
      <>
        <PageHeader folio="Statement" meta={todayLabel()} title="Your ledger" />
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
  const accountWord = data.proof.accountsChecked === 1 ? 'account' : 'accounts';
  const entryWord = data.proof.entryCount === 1 ? 'entry' : 'entries';

  return (
    <>
      <PageHeader
        folio="Statement"
        meta={todayLabel()}
        title="Your ledger, as posted"
        lede="Balances, postings and proof in one ledger."
        actions={
          hasAccounts ? (
            <button type="button" className="btn btn--ghost" onClick={() => setOpenAccount(true)}>
              <PlusIcon width={16} height={16} />
              Open account
            </button>
          ) : undefined
        }
      />

      {hasAccounts ? (
        <>
          <section className="balance" aria-label="Balances">
            <div className="balance__totals">
              {data.totals.map((total) => (
                <div className="balance__item" key={total.currency}>
                  <span className="balance__code">{total.currency}</span>
                  <span className="balance__value">{formatMinor(total.balanceMinor, total.currency)}</span>
                </div>
              ))}
            </div>

            <div className={`balance__status${balanced ? '' : ' balance__status--attention'}`}>
              <StatusMark ok={balanced} />
              <div className="balance__status-copy">
                <p className="balance__status-title">
                  {balanced ? 'Journal balanced' : 'Journal needs attention'}
                </p>
                <p className="balance__status-note">
                  {balanced
                    ? `${data.proof.accountsChecked} ${accountWord} and ${data.proof.entryCount} ${entryWord} checked. Balances are built from posted entries.`
                    : `A difference of ${formatMinor(
                        data.proof.driftMinor,
                        data.totals[0]?.currency ?? 'INR',
                      )} was found.`}
                </p>
              </div>
              <Link className="text-link" to="/wallet/proof">
                Read proof
              </Link>
            </div>
          </section>

          <section className="section" aria-labelledby="accounts-title">
            <div className="section-heading">
              <div>
                <h2 id="accounts-title">Accounts</h2>
                <p>Each account keeps one currency and its own statement.</p>
              </div>
              <span className="section-heading__note">
                {accounts.length} {accounts.length === 1 ? 'account' : 'accounts'}
              </span>
            </div>

            <div className="account-list">
              {accounts.map((account) => (
                <div className="account-row" key={account.accountId}>
                  <span className="account-row__code mono">{account.currency}</span>
                  <div className="account-row__name">
                    <span>{accountLabel(account, accounts)}</span>
                    <span className="account-row__id mono">{shortId(account.accountId)}</span>
                  </div>
                  <span className="account-row__status">
                    <StatusTag status={account.status} />
                  </span>
                  <span className="account-row__balance">
                    {formatMinor(account.balanceMinor, account.currency)}
                  </span>
                  <Link
                    className="account-row__link text-link"
                    to={`/wallet/activity?account=${account.accountId}`}
                  >
                    Statement
                  </Link>
                </div>
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
        </>
      ) : (
        <EmptyState
          title="Open your first account"
          action={
            <button type="button" className="btn btn--primary" onClick={() => setOpenAccount(true)}>
              <PlusIcon width={16} height={16} />
              Open an account
            </button>
          }
        >
          <p>
            A SentinelPay account keeps one currency and a full statement of every posting.
            You can open an INR, USD or EUR account now.
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
