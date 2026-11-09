import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { getAccounts, getActivity } from '../api/client';
import type { Account } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { LedgerTable } from '../components/LedgerTable';
import { TransactionDetailDialog } from '../components/TransactionDetailDialog';
import { AccountChips, accountLabel } from '../components/accounts';
import {
  CopyButton,
  EmptyState,
  ErrorNotice,
  LoadingBlock,
  StatusTag,
} from '../components/ui';
import { formatMinor, shortId } from '../format';

function ordered(accounts: Account[]): Account[] {
  return [...accounts].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

export function ActivityPage() {
  const [params, setParams] = useSearchParams();
  const accountsQuery = useQuery({ queryKey: ['accounts'], queryFn: getAccounts });
  const accounts = ordered(accountsQuery.data ?? []);
  const requested = params.get('account');
  const selected = accounts.some((account) => account.accountId === requested)
    ? (requested as string)
    : (accounts[0]?.accountId ?? '');
  const [selectedTransaction, setSelectedTransaction] = useState<string | null>(null);

  useEffect(() => {
    if (selected && selected !== requested) {
      setParams({ account: selected }, { replace: true });
    }
  }, [selected, requested, setParams]);

  const activity = useInfiniteQuery({
    queryKey: ['activity', selected],
    queryFn: ({ pageParam }) => getActivity(selected, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: Boolean(selected),
  });

  if (accountsQuery.isPending) {
    return <LoadingBlock label="Opening your statement…" />;
  }

  if (accountsQuery.error) {
    return (
      <>
        <PageHeader folio="Statement" title="Activity" />
        <ErrorNotice>
          {accountsQuery.error instanceof Error
            ? accountsQuery.error.message
            : 'Your accounts could not be read.'}
        </ErrorNotice>
      </>
    );
  }

  if (accounts.length === 0) {
    return (
      <>
        <PageHeader folio="Statement" title="Activity" />
        <EmptyState
          title="No statement yet"
          action={
            <Link className="btn btn--primary" to="/wallet">
              Open an account
            </Link>
          }
        >
          <p>Open an account and its postings will appear here, newest first.</p>
        </EmptyState>
      </>
    );
  }

  const account = accounts.find((candidate) => candidate.accountId === selected) ?? accounts[0];
  const items = activity.data?.pages.flatMap((page) => page.items) ?? [];
  const hasNextPage = activity.hasNextPage;

  return (
    <>
      <PageHeader
        folio="Statement"
        meta={accountLabel(account, accounts)}
        title="Account activity"
        lede="Every posting to this account, newest first. Open a row to see both sides of the entry."
        actions={
          <Link className="btn btn--primary" to="/wallet/send">
            Send money
          </Link>
        }
      />

      <AccountChips
        accounts={accounts}
        selected={selected}
        onSelect={(accountId) => setParams({ account: accountId }, { replace: true })}
      />

      <section className="statement-meta" aria-label="Account summary">
        <div className="statement-meta__item">
          <span className="statement-meta__label">Closing balance</span>
          <span className="statement-meta__value">
            {formatMinor(account.balanceMinor, account.currency)}
          </span>
        </div>
        <div className="statement-meta__item">
          <span className="statement-meta__label">Account ID</span>
          <span className="statement-meta__id mono">
            {shortId(account.accountId)}
            <CopyButton value={account.accountId} label="Copy" />
          </span>
        </div>
        <div className="statement-meta__item">
          <span className="statement-meta__label">Status</span>
          <StatusTag status={account.status} />
        </div>
      </section>

      {activity.isPending ? <LoadingBlock label="Reading postings…" /> : null}

      {activity.error ? (
        <ErrorNotice>
          {activity.error instanceof Error
            ? activity.error.message
            : 'The statement could not be read.'}
        </ErrorNotice>
      ) : null}

      {!activity.isPending && !activity.error && items.length === 0 ? (
        <p className="quiet">
          This account has no postings yet. Open it with a deposit or receive a transfer.
        </p>
      ) : null}

      {items.length > 0 ? (
        <LedgerTable
          items={items}
          accounts={accounts}
          onSelect={(item) => setSelectedTransaction(item.transactionId)}
        />
      ) : null}

      {hasNextPage ? (
        <div className="load-more">
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => void activity.fetchNextPage()}
            disabled={activity.isFetchingNextPage}
          >
            {activity.isFetchingNextPage ? 'Loading older postings…' : 'Load older postings'}
          </button>
        </div>
      ) : items.length > 0 ? (
        <p className="end-note">That is the beginning of this statement.</p>
      ) : null}

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
