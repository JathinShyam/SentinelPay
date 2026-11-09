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

export function StatusMark({ ok }: { ok: boolean }) {
  return (
    <span className={`status-mark${ok ? ' status-mark--ok' : ' status-mark--attention'}`} aria-hidden="true">
      {ok ? <CheckIcon width={14} height={14} /> : <AlertIcon width={14} height={14} />}
    </span>
  );
}

export function StatusTag({ status }: { status: string }) {
  const tone =
    status === 'POSTED' || status === 'ACTIVE'
      ? 'posted'
      : status === 'REVERSED' || status === 'FROZEN'
        ? 'reversed'
        : 'failed';
  return (
    <span className={`tag tag--${tone}`}>
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </span>
  );
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
      {tone === 'error' ? <AlertIcon width={17} height={17} /> : null}
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
      <CopyIcon width={14} height={14} />
      {copied ? 'Copied' : label}
    </button>
  );
}
