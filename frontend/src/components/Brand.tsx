import { LedgerMark } from './icons';

export function Brand({ light = false }: { light?: boolean }) {
  return (
    <div className={`brand${light ? ' brand--light' : ''}`}>
      <span className="brand__stamp" aria-hidden="true">
        <LedgerMark width={22} height={22} />
      </span>
      <span className="brand__text">
        <span className="brand__name">SentinelPay</span>
        <span className="brand__sub">Ledger wallet</span>
      </span>
    </div>
  );
}
