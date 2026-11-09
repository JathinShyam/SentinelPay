import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getTransaction } from '../api/client';
import type { Account } from '../api/types';
import { formatDateTime, formatMinor, shortId } from '../format';
import { accountLabel } from './accounts';
import { CopyButton, ErrorNotice, LoadingBlock, StatusTag } from './ui';
import { CloseIcon } from './icons';

export function TransactionDetailDialog({
  transactionId,
  accounts,
  onClose,
}: {
  transactionId: string;
  accounts: Account[];
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const { data, isPending, error } = useQuery({
    queryKey: ['transaction', transactionId],
    queryFn: () => getTransaction(transactionId),
  });

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) {
      element.showModal();
    }
  }, []);

  useEffect(() => {
    const element = dialog.current;
    if (!element) {
      return;
    }
    const handleClose = () => onClose();
    element.addEventListener('close', handleClose);
    return () => element.removeEventListener('close', handleClose);
  }, [onClose]);

  function close() {
    dialog.current?.close();
  }

  const amount = data?.entries
    .filter((entry) => entry.direction === 'DEBIT')
    .reduce((total, entry) => total + entry.amountMinor, 0) ?? 0;

  return (
    <dialog
      ref={dialog}
      className="detail"
      aria-labelledby="detail-title"
      onClick={(event) => {
        if (event.target === dialog.current) {
          close();
        }
      }}
    >
      <div className="detail__sheet">
        <header className="detail__head">
          <div>
            <p className="folio">Transaction record</p>
            <h2 id="detail-title">{data ? data.type.toLowerCase() : 'Reading posting'}</h2>
          </div>
          <button type="button" className="icon-button" onClick={close} aria-label="Close transaction">
            <CloseIcon />
          </button>
        </header>

        {isPending ? <LoadingBlock label="Fetching journal entries…" /> : null}
        {error ? <ErrorNotice>{error.message}</ErrorNotice> : null}

        {data ? (
          <>
            <div className="detail__amount">
              <span className="detail__amount-label">
                {data.entries.some((entry) => entry.direction === 'DEBIT') ? 'Debited' : 'Credited'}
              </span>
              <span className="detail__amount-value money">
                {formatMinor(amount, data.currency)}
              </span>
              <StatusTag status={data.status} />
            </div>

            <dl className="detail__meta">
              <div>
                <dt>Posted</dt>
                <dd>{formatDateTime(data.createdAt)}</dd>
              </div>
              <div>
                <dt>Note</dt>
                <dd>{data.description?.trim() || 'No note attached'}</dd>
              </div>
              <div>
                <dt>Transaction</dt>
                <dd className="mono">
                  {shortId(data.transactionId)}
                  <CopyButton value={data.transactionId} label="Copy ID" />
                </dd>
              </div>
              {data.reversalOf ? (
                <div>
                  <dt>Reverses</dt>
                  <dd className="mono">{shortId(data.reversalOf)}</dd>
                </div>
              ) : null}
              {data.reversedBy ? (
                <div>
                  <dt>Reversed by</dt>
                  <dd className="mono">{shortId(data.reversedBy)}</dd>
                </div>
              ) : null}
            </dl>

            <section className="detail__journal" aria-labelledby="journal-title">
              <div className="section-heading">
                <h3 id="journal-title">Journal entries</h3>
                <p>Every posting has a debit and a credit of equal value.</p>
              </div>
              <table className="journal">
                <thead>
                  <tr>
                    <th scope="col">Account</th>
                    <th scope="col">Side</th>
                    <th scope="col" className="num">
                      Amount
                    </th>
                    <th scope="col" className="num">
                      Balance after
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.entries.map((entry) => {
                    const own = accounts.find((account) => account.accountId === entry.accountId);
                    return (
                      <tr key={entry.entryId}>
                        <td>
                          <span>{own ? accountLabel(own, accounts) : `Account ${shortId(entry.accountId)}`}</span>
                          <span className="journal__id mono">{shortId(entry.entryId)}</span>
                        </td>
                        <td>
                          <span className={`side side--${entry.direction.toLowerCase()}`}>
                            {entry.direction === 'DEBIT' ? 'Debit' : 'Credit'}
                          </span>
                        </td>
                        <td className="num">{formatMinor(entry.amountMinor, data.currency)}</td>
                        <td className="num">
                          {entry.runningBalanceMinor == null
                            ? '—'
                            : formatMinor(entry.runningBalanceMinor, data.currency)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <p className="detail__note">
                Balances after posting are shown only for accounts you can access. The
                transaction itself still proves both sides of the entry.
              </p>
            </section>
          </>
        ) : null}
      </div>
    </dialog>
  );
}
