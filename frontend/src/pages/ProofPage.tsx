import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getAccounts, getAudit } from '../api/client';
import type { Account } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { AccountChips, accountLabel } from '../components/accounts';
import {
  EmptyState,
  ErrorNotice,
  LoadingBlock,
  StatusMark,
  StatusTag,
} from '../components/ui';
import { RefreshIcon } from '../components/icons';
import { formatDateTime, formatMinor } from '../format';

function ordered(accounts: Account[]): Account[] {
  return [...accounts].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

export function ProofPage() {
  const [params, setParams] = useSearchParams();
  const accountsQuery = useQuery({ queryKey: ['accounts'], queryFn: getAccounts });
  const accounts = ordered(accountsQuery.data ?? []);
  const requested = params.get('account');
  const selected = accounts.some((account) => account.accountId === requested)
    ? (requested as string)
    : (accounts[0]?.accountId ?? '');
  const [refreshing, setRefreshing] = useState(false);

  const audit = useQuery({
    queryKey: ['audit', selected],
    queryFn: () => getAudit(selected),
    enabled: Boolean(selected),
  });

  if (accountsQuery.isPending) {
    return <LoadingBlock label="Opening the proof page…" />;
  }

  if (accountsQuery.error) {
    return (
      <>
        <PageHeader folio="Certificate" title="Proof" />
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
        <PageHeader folio="Certificate" title="Proof" />
        <EmptyState
          title="Nothing to certify yet"
          action={
            <Link className="btn btn--primary" to="/wallet">
              Open an account
            </Link>
          }
        >
          <p>
            Once you have an account, this page compares its stored balance with the journal
            and certifies the result.
          </p>
        </EmptyState>
      </>
    );
  }

  const account = accounts.find((candidate) => candidate.accountId === selected) ?? accounts[0];
  const balanced = audit.data?.driftMinor === 0 && audit.data.transactionsBalanced;

  async function recheck() {
    setRefreshing(true);
    try {
      await audit.refetch();
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <>
      <PageHeader
        folio="Certificate"
        meta={accountLabel(account, accounts)}
        title="Proof of journal"
        lede="The stored balance is only trustworthy if it equals the journal. This page checks both."
        actions={
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => void recheck()}
            disabled={refreshing || audit.isFetching}
          >
            <RefreshIcon width={16} height={16} />
            {refreshing || audit.isFetching ? 'Checking…' : 'Recheck'}
          </button>
        }
      />

      <AccountChips
        accounts={accounts}
        selected={selected}
        onSelect={(accountId) => setParams({ account: accountId }, { replace: true })}
      />

      {audit.isPending ? <LoadingBlock label="Comparing balance and journal…" /> : null}
      {audit.error ? (
        <ErrorNotice>
          {audit.error instanceof Error ? audit.error.message : 'The proof could not be read.'}
        </ErrorNotice>
      ) : null}

      {audit.data ? (
        <article className="proof-sheet" aria-labelledby="certificate-title">
          <header className="proof-sheet__head">
            <div>
              <p className="folio">Certificate of balance</p>
              <h2 id="certificate-title">
                {balanced ? 'This account is in balance.' : 'This account does not balance.'}
              </h2>
            </div>
            <StatusTag status={account.status} />
          </header>
          <p className="proof-sheet__id mono">{audit.data.accountId}</p>

          <div className="reconcile">
            <div className="reconcile__side">
              <p className="reconcile__label">Stored balance</p>
              <p className="reconcile__value">
                {formatMinor(audit.data.materializedBalanceMinor, account.currency)}
              </p>
            </div>
            <div className="reconcile__side">
              <p className="reconcile__label">Journal balance</p>
              <p className="reconcile__value">
                {formatMinor(audit.data.ledgerBalanceMinor, account.currency)}
              </p>
            </div>
          </div>

          <div className={`reconcile__result${balanced ? '' : ' reconcile__result--attention'}`}>
            <StatusMark ok={Boolean(balanced)} />
            <p className="reconcile__difference">
              Difference <span>{formatMinor(audit.data.driftMinor, account.currency)}</span>
            </p>
            <p className="reconcile__note">
              {balanced
                ? 'Every linked transaction nets to zero.'
                : 'Inspect this account before moving money.'}
            </p>
          </div>

          <dl className="proof-sheet__meta">
            <div>
              <dt>Currency</dt>
              <dd>{account.currency}</dd>
            </div>
            <div>
              <dt>Journal entries</dt>
              <dd>{audit.data.entryCount}</dd>
            </div>
            <div>
              <dt>Transactions balanced</dt>
              <dd>{audit.data.transactionsBalanced ? 'Yes' : 'No'}</dd>
            </div>
            <div>
              <dt>Checked</dt>
              <dd>{formatDateTime(audit.data.checkedAt)}</dd>
            </div>
          </dl>

          <footer className="proof-sheet__foot">
            <div>
              <h3>How to read this</h3>
              <p>
                The stored balance is the number kept on the account. The journal balance is
                the sum of every debit and credit posted to it. SentinelPay treats the account
                as sound only when the difference is zero and every linked transaction nets to
                zero.
              </p>
            </div>
            <button type="button" className="btn btn--ghost" onClick={() => window.print()}>
              Print certificate
            </button>
          </footer>
        </article>
      ) : null}
    </>
  );
}
