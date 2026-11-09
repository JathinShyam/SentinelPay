import type { Account, ActivityItem } from '../api/types';
import { formatDate, formatMinor, formatTime, shortId } from '../format';
import { accountLabel } from './accounts';
import { StatusTag } from './ui';
import { ArrowDownLeftIcon, ArrowUpRightIcon, ChevronRightIcon } from './icons';

function particulars(item: ActivityItem): string {
  if (item.type === 'DEPOSIT') {
    return 'Opening deposit';
  }
  if (item.type === 'REVERSAL') {
    return 'Reversal';
  }
  if (item.description?.trim()) {
    return item.description.trim();
  }
  return item.direction === 'DEBIT' ? 'Transfer out' : 'Transfer in';
}

function counterparty(item: ActivityItem, accounts: Account[]): string {
  if (!item.counterpartyAccountId) {
    return 'System reserve';
  }
  const own = accounts.find((account) => account.accountId === item.counterpartyAccountId);
  if (own) {
    return accountLabel(own, accounts);
  }
  return `Account ${shortId(item.counterpartyAccountId)}`;
}

export function LedgerTable({
  items,
  accounts,
  onSelect,
  caption = 'Account statement',
}: {
  items: ActivityItem[];
  accounts: Account[];
  onSelect: (item: ActivityItem) => void;
  caption?: string;
}) {
  return (
    <div className="ledger-wrap">
      <table className="ledger">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Posted</th>
            <th scope="col">Particulars</th>
            <th scope="col" className="num">
              Debit
            </th>
            <th scope="col" className="num">
              Credit
            </th>
            <th scope="col" className="num">
              Balance
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const incoming = item.direction === 'CREDIT';
            return (
              <tr key={item.entryId}>
                <td className="ledger__date">
                  <time dateTime={item.createdAt}>{formatDate(item.createdAt)}</time>
                  <span>{formatTime(item.createdAt)}</span>
                </td>
                <td className="ledger__particulars">
                  <button
                    type="button"
                    className="ledger__link"
                    onClick={() => onSelect(item)}
                    aria-label={`Open ${particulars(item)}, ${formatMinor(
                      item.amountMinor,
                      item.currency,
                    )}`}
                  >
                    <span className="ledger__icon" aria-hidden="true">
                      {incoming ? (
                        <ArrowDownLeftIcon width={16} height={16} />
                      ) : (
                        <ArrowUpRightIcon width={16} height={16} />
                      )}
                    </span>
                    <span className="ledger__text">
                      <span className="ledger__primary">{particulars(item)}</span>
                      <span className="ledger__secondary">
                        {counterparty(item, accounts)}
                        <span className="ledger__ref"> · {shortId(item.transactionId)}</span>
                      </span>
                    </span>
                    <ChevronRightIcon width={16} height={16} className="ledger__chevron" />
                  </button>
                  <StatusTag status={item.status} />
                </td>
                <td className="num ledger__debit" data-label="Debit">
                  {incoming ? <span className="muted">—</span> : formatMinor(item.amountMinor, item.currency)}
                </td>
                <td className="num ledger__credit" data-label="Credit">
                  {incoming ? formatMinor(item.amountMinor, item.currency) : <span className="muted">—</span>}
                </td>
                <td className="num ledger__balance" data-label="Balance">
                  {formatMinor(item.balanceAfterMinor, item.currency)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
