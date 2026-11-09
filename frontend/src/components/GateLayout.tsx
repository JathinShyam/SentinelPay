import type { ReactNode } from 'react';
import { Brand } from './Brand';

export function GateLayout({ children }: { children: ReactNode }) {
  return (
    <div className="gate">
      <section className="gate__cover">
        <Brand light />
        <div className="gate__pitch">
          <p className="folio folio--light">Customer ledger</p>
          <h1>
            Money,
            <br />
            on the record.
          </h1>
          <p className="gate__lede">
            Every balance in this wallet is the sum of postings you can inspect. No
            vanishing balances, no mystery adjustments.
          </p>
        </div>
        <dl className="gate__facts">
          <div>
            <dt>Double entry</dt>
            <dd>Every transfer debits one account and credits another.</dd>
          </div>
          <div>
            <dt>Append-only</dt>
            <dd>Posted entries cannot be edited or deleted.</dd>
          </div>
          <div>
            <dt>Verifiable</dt>
            <dd>Compare the stored balance with the journal whenever you want.</dd>
          </div>
        </dl>
        <p className="gate__foot mono">PostgreSQL · Kafka · immutable journal</p>
      </section>
      <main className="gate__form">{children}</main>
    </div>
  );
}
