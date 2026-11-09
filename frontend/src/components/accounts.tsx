import type { Account } from '../api/types';
import { formatMinor } from '../format';

export function accountLabel(account: Account, accounts: Account[]): string {
  const ordered = [...accounts].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const index = ordered.findIndex((candidate) => candidate.accountId === account.accountId);
  return `${account.currency} account ${index >= 0 ? index + 1 : ''}`.trim();
}

export function AccountChips({
  accounts,
  selected,
  onSelect,
  label = 'Choose an account',
}: {
  accounts: Account[];
  selected: string;
  onSelect: (accountId: string) => void;
  label?: string;
}) {
  if (accounts.length === 0) {
    return null;
  }
  return (
    <nav className="account-tabs" aria-label={label}>
      {accounts.map((account) => {
        const active = account.accountId === selected;
        return (
          <button
            key={account.accountId}
            type="button"
            className={`account-tab${active ? ' account-tab--active' : ''}`}
            aria-pressed={active}
            onClick={() => onSelect(account.accountId)}
          >
            <span className="account-tab__name">{accountLabel(account, accounts)}</span>
            <span className="account-tab__balance money">
              {formatMinor(account.balanceMinor, account.currency)}
            </span>
          </button>
        );
      })}
    </nav>
  );
}
