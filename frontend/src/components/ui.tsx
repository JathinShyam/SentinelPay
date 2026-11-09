import { useState, type ReactNode } from 'react';
import { formatMinor } from '../format';
import { AlertIcon, CheckIcon, CopyIcon } from './icons';

export function Money({
  minor,
  currency,
  signed = false,
  className = '',
}: {
  minor: number;
  currency: string;
  signed?: boolean;
  className?: string;
}) {
  return (
    <span className={`money ${className}`.trim()}>
      {signed && minor > 0 ? '+' : ''}
      {formatMinor(minor, currency)}
    </span>
  );
}

export function Seal({
  state = 'balanced',
  label,
  detail,
}: {
  state?: 'balanced' | 'attention';
  label?: string;
  detail?: string;
}) {
  const balanced = state === 'balanced';
  return (
    <div className={`seal seal--${state}`} role="status">
      <div className="seal__ring">
        {balanced ? <CheckIcon width={18} height={18} /> : <AlertIcon width={18} height={18} />}
        <strong>{label ?? (balanced ? 'Balanced' : 'Attention')}</strong>
        <small>{detail ?? (balanced ? 'journal verified' : 'drift detected')}</small>
      </div>
    </div>
  );
}

export function StatusTag({ status }: { status: string }) {
  const tone =
    status === 'POSTED' || status === 'ACTIVE'
      ? 'posted'
      : status === 'REVERSED' || status === 'FROZEN'
        ? 'reversed'
        : 'failed';
  return <span className={`tag tag--${tone}`}>{status.charAt(0) + status.slice(1).toLowerCase()}</span>;
}

export function ErrorNotice({
  children,
  tone = 'error',
}: {
  children: ReactNode;
  tone?: 'error' | 'info';
}) {
  return (
    <div className={`notice notice--${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      {tone === 'error' ? <AlertIcon width={18} height={18} /> : null}
      <div>{children}</div>
    </div>
  );
}

export function LoadingBlock({ label = 'Reading the ledger…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <span className="loading__rule" aria-hidden="true" />
      <p>{label}</p>
    </div>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty__rule" aria-hidden="true" />
      <h2>{title}</h2>
      <div className="empty__body">{children}</div>
      {action ? <div className="empty__action">{action}</div> : null}
    </div>
  );
}

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      setCopied(false);
    }
  }

  return (
    <button type="button" className="copy" onClick={copy} aria-live="polite">
      <CopyIcon width={15} height={15} />
      {copied ? 'Copied' : label}
    </button>
  );
}
