import { LedgerMark } from './icons';

export function Brand() {
  return (
    <span className="brand">
      <span className="brand__mark" aria-hidden="true">
        <LedgerMark width={21} height={21} />
      </span>
      <span className="brand__name">SentinelPay</span>
    </span>
  );
}
