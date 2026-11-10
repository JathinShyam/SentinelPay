import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, createTransfer, getOverview } from '../api/client';
import type { TransferResult } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { accountLabel } from '../components/accounts';
import { EmptyState, ErrorNotice, LoadingBlock, Money, StatusTag } from '../components/ui';
import { CheckIcon, SendIcon } from '../components/icons';
import { formatDateTime, formatMinor, parseAmount, shortId } from '../format';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function symbolFor(currency?: string): string {
  if (currency === 'INR') {
    return '₹';
  }
  if (currency === 'USD') {
    return '$';
  }
  if (currency === 'EUR') {
    return '€';
  }
  return '';
}

function transferError(failure: unknown): string {
  if (failure instanceof ApiError) {
    switch (failure.code) {
      case 'INSUFFICIENT_FUNDS':
        return 'That account does not have enough money for this transfer.';
      case 'ACCOUNT_NOT_FOUND':
        return 'That account was not found. Check the account ID and try again.';
      case 'CURRENCY_MISMATCH':
        return 'The two accounts use different currencies. Pick an account in the same currency.';
      case 'ACCOUNT_NOT_ACTIVE':
        return 'That account is frozen or closed, so it cannot send money.';
      case 'IDEMPOTENCY_CONFLICT':
        return 'This transfer reference was already used for a different amount. Start the transfer again.';
      case 'FORBIDDEN':
        return 'This session cannot post that transfer.';
      case 'UNAUTHENTICATED':
        return 'Your session ended. Sign in again to send money.';
      default:
        return failure.message;
    }
  }
  return failure instanceof Error ? failure.message : 'The transfer could not be posted.';
}

export function SendPage() {
  const queryClient = useQueryClient();
  const { data, isPending, error } = useQuery({ queryKey: ['overview'], queryFn: getOverview });

  const [sourceId, setSourceId] = useState('');
  const [mode, setMode] = useState<'own' | 'external'>('own');
  const [destinationId, setDestinationId] = useState('');
  const [externalId, setExternalId] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [stage, setStage] = useState<'compose' | 'review' | 'receipt'>('compose');
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState<TransferResult | null>(null);
  const idempotencyKey = useRef<string>(crypto.randomUUID());

  const accounts = data
    ? [...data.accounts].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    : [];

  useEffect(() => {
    if (!sourceId && accounts.length > 0) {
      setSourceId(accounts[0].accountId);
    }
  }, [accounts, sourceId]);

  const source = accounts.find((account) => account.accountId === sourceId);
  const destinations = source
    ? accounts.filter(
        (account) =>
          account.accountId !== source.accountId && account.currency === source.currency,
      )
    : [];

  useEffect(() => {
    if (destinationId && !destinations.some((account) => account.accountId === destinationId)) {
      setDestinationId('');
    }
  }, [destinationId, destinations]);

  if (isPending) {
    return <LoadingBlock label="Preparing a payment order…" />;
  }

  if (error || !data) {
    return (
      <>
        <PageHeader folio="Payment order" title="Send money" />
        <ErrorNotice>
          {error instanceof Error ? error.message : 'Your accounts could not be read.'}
        </ErrorNotice>
      </>
    );
  }

  if (accounts.length === 0) {
    return (
      <>
        <PageHeader folio="Payment order" title="Send money" />
        <EmptyState
          title="You need an account first"
          action={
            <Link className="btn btn--primary" to="/wallet">
              Go to your accounts
            </Link>
          }
        >
          <p>Open an account with a balance, then come back to send money from it.</p>
        </EmptyState>
      </>
    );
  }

  function chooseSource(accountId: string) {
    setSourceId(accountId);
    setFormError(null);
  }

  function startReview() {
    setFormError(null);
    if (!source) {
      setFormError('Choose the account you want to send from.');
      return;
    }
    const destination = mode === 'own' ? destinationId : externalId.trim();
    if (!destination) {
      setFormError(
        mode === 'own' ? 'Choose the account you want to pay.' : 'Enter the destination account ID.',
      );
      return;
    }
    if (mode === 'external' && !UUID_PATTERN.test(destination)) {
      setFormError('That does not look like an account ID. Check it and try again.');
      return;
    }
    if (destination === source.accountId) {
      setFormError('Choose a different destination account.');
      return;
    }
    const parsed = parseAmount(amount);
    if ('error' in parsed) {
      setFormError(parsed.error);
      return;
    }
    idempotencyKey.current = crypto.randomUUID();
    setStage('review');
  }

  async function postTransfer() {
    if (!source) {
      return;
    }
    const parsed = parseAmount(amount);
    if ('error' in parsed) {
      setFormError(parsed.error);
      return;
    }
    const destination = mode === 'own' ? destinationId : externalId.trim();
    setPending(true);
    setFormError(null);
    try {
      const result = await createTransfer({
        sourceAccountId: source.accountId,
        destinationAccountId: destination,
        amountMinor: parsed.minor,
        currency: source.currency,
        description: note.trim() || undefined,
        idempotencyKey: idempotencyKey.current,
      });
      setReceipt(result);
      setStage('receipt');
      await queryClient.invalidateQueries();
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'IDEMPOTENCY_CONFLICT') {
        idempotencyKey.current = crypto.randomUUID();
      }
      setFormError(transferError(failure));
    } finally {
      setPending(false);
    }
  }

  function reset() {
    setStage('compose');
    setReceipt(null);
    setAmount('');
    setNote('');
    setFormError(null);
    idempotencyKey.current = crypto.randomUUID();
  }

  const parsedAmount = parseAmount(amount);
  const amountMinor = 'minor' in parsedAmount ? parsedAmount.minor : 0;
  const destinationAccount =
    mode === 'own'
      ? destinations.find((account) => account.accountId === destinationId)
      : undefined;
  const destinationLabel =
    mode === 'own'
      ? destinationAccount
        ? accountLabel(destinationAccount, accounts)
        : 'Choose an account'
      : externalId
        ? shortId(externalId)
        : 'External account';

  return (
    <>
      <PageHeader
        folio="Payment order"
        meta={stage === 'compose' ? 'Draft' : stage === 'review' ? 'Ready to post' : 'Posted'}
        title={stage === 'receipt' ? 'Transfer posted' : stage === 'review' ? 'Review transfer' : 'Send money'}
        lede={
          stage === 'receipt'
            ? 'Both sides of the entry are now part of the journal.'
            : stage === 'review'
              ? 'Check the details. Posting writes both sides to the journal.'
              : 'Move money between your accounts or to another account. You review every detail before it is posted.'
        }
      />

      <div className="send-layout">
        <div className="send-main">
          {formError ? <ErrorNotice>{formError}</ErrorNotice> : null}

          {stage === 'compose' ? (
            <section className="order" aria-labelledby="order-title">
              <header className="order__head">
                <h2 id="order-title">Transfer details</h2>
                <span className="order__no mono">Ref {shortId(idempotencyKey.current)}</span>
              </header>

              <fieldset className="order__field">
                <legend>From</legend>
                <div className="option-list">
                  {accounts.map((account) => {
                    const active = account.accountId === sourceId;
                    return (
                      <button
                        key={account.accountId}
                        type="button"
                        className={`option${active ? ' option--active' : ''}`}
                        onClick={() => chooseSource(account.accountId)}
                        aria-pressed={active}
                      >
                        <span className="option__name">{accountLabel(account, accounts)}</span>
                        <span className="option__balance money">
                          {formatMinor(account.balanceMinor, account.currency)}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </fieldset>

              <fieldset className="order__field">
                <legend>To</legend>
                <div className="segmented" role="group" aria-label="Destination type">
                  <button
                    type="button"
                    aria-pressed={mode === 'own'}
                    className={`segmented__button${mode === 'own' ? ' segmented__button--active' : ''}`}
                    onClick={() => {
                      setMode('own');
                      setFormError(null);
                    }}
                  >
                    Your accounts
                  </button>
                  <button
                    type="button"
                    aria-pressed={mode === 'external'}
                    className={`segmented__button${
                      mode === 'external' ? ' segmented__button--active' : ''
                    }`}
                    onClick={() => {
                      setMode('external');
                      setFormError(null);
                    }}
                  >
                    Another account
                  </button>
                </div>

                {mode === 'own' ? (
                  destinations.length > 0 ? (
                    <div className="option-list">
                      {destinations.map((account) => {
                        const active = account.accountId === destinationId;
                        return (
                          <button
                            key={account.accountId}
                            type="button"
                            className={`option${active ? ' option--active' : ''}`}
                            onClick={() => setDestinationId(account.accountId)}
                            aria-pressed={active}
                          >
                            <span className="option__name">{accountLabel(account, accounts)}</span>
                            <span className="option__balance money">
                              {formatMinor(account.balanceMinor, account.currency)}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  ) : (
                    <p className="quiet">
                      You do not have another {source?.currency} account. Open one from the
                      overview, or send to an account ID instead.
                    </p>
                  )
                ) : (
                  <label className="field">
                    <span className="field__label">Destination account ID</span>
                    <input
                      className="field__input mono"
                      type="text"
                      name="destinationAccountId"
                      value={externalId}
                      onChange={(event) => setExternalId(event.target.value)}
                      placeholder="00000000-0000-0000-0000-000000000000"
                      autoComplete="off"
                      autoCapitalize="none"
                      spellCheck={false}
                    />
                    <span className="field__hint">
                      The recipient can copy this from their statement. Ask them for it
                      directly; there is no username lookup.
                    </span>
                  </label>
                )}
              </fieldset>

              <label className="order__amount">
                <span className="order__amount-label">Amount</span>
                <span className="order__amount-input">
                  <span className="order__currency" aria-hidden="true">
                    {symbolFor(source?.currency)}
                  </span>
                  <input
                    type="text"
                    name="amount"
                    inputMode="decimal"
                    value={amount}
                    onChange={(event) => setAmount(event.target.value)}
                    placeholder="0.00"
                    aria-label="Amount"
                    autoComplete="off"
                  />
                  <span className="order__code mono">{source?.currency}</span>
                </span>
              </label>

              <label className="field">
                <span className="field__label">Note (optional)</span>
                <input
                  className="field__input"
                  type="text"
                  name="note"
                  value={note}
                  maxLength={140}
                  onChange={(event) => setNote(event.target.value)}
                  autoComplete="off"
                />
                <span className="field__hint">The note appears on both statements.</span>
              </label>

              <footer className="order__foot">
                <p>
                  Available in {source?.currency}:{' '}
                  <Money minor={source?.balanceMinor ?? 0} currency={source?.currency ?? 'INR'} />
                </p>
                <button type="button" className="btn btn--primary" onClick={startReview}>
                  Review transfer
                  <SendIcon width={16} height={16} />
                </button>
              </footer>
            </section>
          ) : null}

          {stage === 'review' && source ? (
            <section className="order" aria-labelledby="review-title">
              <header className="order__head">
                <h2 id="review-title">Check the details</h2>
                <span className="order__no mono">Ref {shortId(idempotencyKey.current)}</span>
              </header>

              <dl className="order__review">
                <div>
                  <dt>From</dt>
                  <dd>{accountLabel(source, accounts)}</dd>
                </div>
                <div>
                  <dt>To</dt>
                  <dd>{destinationLabel}</dd>
                </div>
                <div>
                  <dt>Amount</dt>
                  <dd className="order__review-amount">
                    {formatMinor(amountMinor, source.currency)}
                  </dd>
                </div>
                <div>
                  <dt>Note</dt>
                  <dd>{note.trim() || 'No note'}</dd>
                </div>
                <div>
                  <dt>Reference</dt>
                  <dd className="mono">{shortId(idempotencyKey.current)}</dd>
                </div>
              </dl>

              <p className="order__warning">
                Posting this writes a debit and a credit to the journal. It cannot be edited
                afterwards; a correction is a new reversal entry.
              </p>

              <footer className="order__foot order__foot--review">
                <button
                  type="button"
                  className="btn btn--ghost"
                  onClick={() => {
                    setStage('compose');
                    setFormError(null);
                  }}
                  disabled={pending}
                >
                  Back to edit
                </button>
                <button
                  type="button"
                  className="btn btn--primary"
                  onClick={() => void postTransfer()}
                  disabled={pending}
                >
                  {pending ? 'Posting…' : 'Post transfer'}
                </button>
              </footer>
            </section>
          ) : null}

          {stage === 'receipt' && receipt ? (
            <section className="receipt" aria-labelledby="receipt-title" role="status">
              <header className="receipt__head">
                <div>
                  <p className="folio">Transfer receipt</p>
                  <h2 id="receipt-title">Posted to the journal</h2>
                </div>
                <StatusTag status={receipt.status} />
              </header>

              <p className="receipt__amount">
                {formatMinor(receipt.amountMinor, receipt.currency)}
              </p>
              <p className="receipt__line">
                <CheckIcon width={16} height={16} />
                Debit and credit recorded. Reference {shortId(receipt.transactionId)}.
              </p>

              <dl className="receipt__meta">
                <div>
                  <dt>From</dt>
                  <dd>{source ? accountLabel(source, accounts) : '—'}</dd>
                </div>
                <div>
                  <dt>To</dt>
                  <dd>{destinationLabel}</dd>
                </div>
                <div>
                  <dt>Posted</dt>
                  <dd>{formatDateTime(receipt.createdAt)}</dd>
                </div>
                <div>
                  <dt>New balance</dt>
                  <dd className="money">
                    {formatMinor(receipt.sourceBalanceMinorAfter, receipt.currency)}
                  </dd>
                </div>
              </dl>

              <footer className="receipt__foot">
                <Link className="btn btn--ghost" to={`/wallet/activity?account=${source?.accountId}`}>
                  View statement
                </Link>
                <button type="button" className="btn btn--primary" onClick={reset}>
                  Make another transfer
                </button>
              </footer>
            </section>
          ) : null}
        </div>

        <aside className="send-aside" aria-label="How transfers work">
          <p className="folio">Ledger note</p>
          <h2>What happens when you post</h2>
          <ol className="send-aside__steps">
            <li>
              <span className="send-aside__no mono">1</span>
              <p>The source account is locked and checked for sufficient funds.</p>
            </li>
            <li>
              <span className="send-aside__no mono">2</span>
              <p>
                A debit is written to the source and a credit of the same value to the
                destination.
              </p>
            </li>
            <li>
              <span className="send-aside__no mono">3</span>
              <p>
                Both entries share one transaction ID, which you can open from either
                statement.
              </p>
            </li>
          </ol>
          <p className="send-aside__note">
            Money is stored in integer minor units. The wallet formats it for reading; it
            never calculates a balance in the browser.
          </p>
        </aside>
      </div>
    </>
  );
}
