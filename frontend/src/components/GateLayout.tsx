import type { ReactNode } from 'react';
import { Brand } from './Brand';

export function GateLayout({ children }: { children: ReactNode }) {
  return (
    <div className="gate">
      <header className="gate__top">
        <Brand />
        <p className="gate__top-note">Customer ledger</p>
      </header>

      <div className="gate__body">
        <section className="gate__pitch">
          <p className="folio">Double-entry wallet</p>
          <h1>Every balance has a paper trail.</h1>
          <p className="gate__lede">
            A wallet where each balance is the sum of postings you can inspect. Keep your
            accounts, move money, and verify the journal yourself.
          </p>
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
        </section>

        <main className="gate__form">{children}</main>
      </div>

      <footer className="gate__foot">
        <span>SentinelPay</span>
        <span>PostgreSQL</span>
        <span>Kafka</span>
        <span>Append-only journal</span>
      </footer>
    </div>
  );
}
