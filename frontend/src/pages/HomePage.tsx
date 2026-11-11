import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { Brand } from '../components/Brand';
import { StatusMark } from '../components/ui';
import { formatMinor, formatSigned } from '../format';

/*
 * Pre-computed specimen amounts in minor units. The page formats these
 * integers; it never performs money arithmetic in the browser.
 */
const SPECIMEN = {
  openingMinor: 0,
  depositMinor: 300_000,
  outgoingMinor: 80_000,
  incomingMinor: 25_000,
  transferMinor: 25_000,
  afterDepositMinor: 300_000,
  afterOutgoingMinor: 220_000,
  closingMinor: 245_000,
};

const NAV = [
  { href: '#principles', label: 'Principles' },
  { href: '#journal', label: 'The journal' },
  { href: '#proof', label: 'Proof' },
  { href: '#engineering', label: 'Engineering' },
];

const PRINCIPLES = [
  {
    no: '01',
    title: 'Double entry, always',
    body: 'A transfer debits one account and credits another. The two postings share a transaction and net to zero. If they cannot both post, neither does.',
  },
  {
    no: '02',
    title: 'Written once',
    body: 'Posted journal lines are append-only. Nothing is edited or deleted to make a balance look right. A correction is a new reversal that points back to the original.',
  },
  {
    no: '03',
    title: 'Integers, not approximations',
    body: 'Amounts are signed 64-bit integers in minor units. The server computes totals, running balances and proofs; the browser only formats them for display.',
  },
];

const JOURNAL_STEPS = [
  {
    no: '01',
    title: 'Claim the request',
    body: 'An idempotency key is claimed in Redis before any work starts. A retried request cannot post the same transfer twice.',
  },
  {
    no: '02',
    title: 'Lock in order',
    body: 'Account rows are locked in deterministic UUID order, so two transfers moving money in opposite directions cannot deadlock each other.',
  },
  {
    no: '03',
    title: 'Post the pair',
    body: 'Both journal lines append in one PostgreSQL transaction, guarded by a conditional constraint that refuses a negative wallet.',
  },
  {
    no: '04',
    title: 'Publish after commit',
    body: 'The outbox event commits with the journal. Kafka publishes from the outbox afterwards; a broker outage delays events, not the posting.',
  },
];

const PROOF_POINTS = [
  {
    term: 'Stored balance',
    detail: 'The value kept on the account row.',
  },
  {
    term: 'Journal balance',
    detail: 'The sum of every posted debit and credit.',
  },
  {
    term: 'Difference',
    detail: 'Stored minus journal. Zero is the only result that passes.',
  },
  {
    term: 'Transactions',
    detail: 'Every linked transaction must also net to zero.',
  },
];

const ENGINEERING = [
  {
    no: '01',
    title: 'No double spend',
    body: 'Redis ingress idempotency, deterministic UUID lock ordering, row locks and a conditional non-negative balance constraint close the usual race.',
  },
  {
    no: '02',
    title: 'No balance drift',
    body: 'Signed BIGINT minor units from request to database. No floating point, and no money arithmetic in the browser.',
  },
  {
    no: '03',
    title: 'No split brain',
    body: 'Journal lines and their outbox event commit together. Kafka is downstream of PostgreSQL, and retries drain later without losing the posting.',
  },
  {
    no: '04',
    title: 'No silent mismatch',
    body: 'End-of-day clearing files reconcile against posted entries with bounded virtual-thread concurrency. Each customer account can be proved on its own.',
  },
];

export function HomePage() {
  const { status } = useAuth();
  const authenticated = status === 'authenticated';
  const primaryTo = authenticated ? '/wallet' : '/register';

  return (
    <div className="home" id="top">
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      <header className="home-top">
        <Link className="brand-home" to="/">
          <Brand />
        </Link>

        <nav className="home-nav" aria-label="Sections">
          {NAV.map((item) => (
            <a key={item.href} href={item.href}>
              {item.label}
            </a>
          ))}
        </nav>

        <div className="home-top__actions">
          {!authenticated ? (
            <Link className="home-signin" to="/login">
              Sign in
            </Link>
          ) : null}
          <Link className="btn btn--primary home-top__cta" to={primaryTo}>
            {authenticated ? 'Open wallet' : 'Open a ledger'}
          </Link>
        </div>
      </header>

      <main id="main" tabIndex={-1}>
        <section className="home-hero" aria-labelledby="home-title">
          <div className="home-wrap home-hero__inner">
            <div className="home-hero__copy">
              <p className="folio">Double-entry wallet · settlement ledger</p>
              <h1 id="home-title">Every balance has a paper trail.</h1>
              <p className="home-hero__lede">
                SentinelPay is a wallet built on an append-only journal. Every movement is
                posted twice, written once, and can be checked against the statement it
                produced.
              </p>
              <div className="home-hero__actions">
                <Link className="btn btn--primary" to={primaryTo}>
                  {authenticated ? 'Open your wallet' : 'Open your ledger'}
                </Link>
                <a className="btn btn--ghost" href="#journal">
                  How the journal works
                </a>
              </div>

              <dl className="home-hero__facts">
                <div>
                  <dt>Accounts</dt>
                  <dd>INR · USD · EUR</dd>
                </div>
                <div>
                  <dt>Posting</dt>
                  <dd>Double entry</dd>
                </div>
                <div>
                  <dt>History</dt>
                  <dd>Append-only</dd>
                </div>
              </dl>

              <p className="home-hero__note">
                An engineering project, not a bank. Figures on this page are specimens.
              </p>
            </div>

            <figure className="home-hero__specimen">
              <figcaption className="specimen-caption">
                <span>Specimen statement · illustrative figures</span>
                <span>No. 01</span>
              </figcaption>

              <article className="statement" aria-labelledby="statement-title">
                <header className="statement__head">
                  <div>
                    <p className="statement__kicker">Customer statement</p>
                    <h2 id="statement-title">INR account · 2041</h2>
                  </div>
                  <span className="specimen-stamp" aria-hidden="true">
                    Specimen
                  </span>
                </header>

                <dl className="statement__meta">
                  <div>
                    <dt>Period</dt>
                    <dd>01–08 Nov 2026</dd>
                  </div>
                  <div>
                    <dt>Opening balance</dt>
                    <dd>{formatMinor(SPECIMEN.openingMinor, 'INR')}</dd>
                  </div>
                  <div>
                    <dt>Currency</dt>
                    <dd>INR</dd>
                  </div>
                </dl>

                <table className="statement__table">
                  <caption className="visually-hidden">
                    Specimen account statement with three posted entries and a closing balance
                    of {formatMinor(SPECIMEN.closingMinor, 'INR')}.
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Date</th>
                      <th scope="col">Particulars</th>
                      <th scope="col" className="num">
                        Amount
                      </th>
                      <th scope="col" className="num">
                        Balance
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <th scope="row" className="statement__date">
                        <time dateTime="2026-11-02">02 Nov</time>
                      </th>
                      <td className="statement__particulars">
                        <span className="statement__primary">Opening deposit</span>
                        <span className="statement__ref mono">SP·CR·1042</span>
                      </td>
                      <td className="statement__amount statement__amount--credit num">
                        {formatSigned(SPECIMEN.depositMinor, 'INR', 'CREDIT')}
                      </td>
                      <td className="statement__balance num" data-label="Balance">
                        {formatMinor(SPECIMEN.afterDepositMinor, 'INR')}
                      </td>
                    </tr>
                    <tr>
                      <th scope="row" className="statement__date">
                        <time dateTime="2026-11-05">05 Nov</time>
                      </th>
                      <td className="statement__particulars">
                        <span className="statement__primary">Transfer to A. Menon</span>
                        <span className="statement__ref mono">SP·DR·1187</span>
                      </td>
                      <td className="statement__amount statement__amount--debit num">
                        {formatSigned(SPECIMEN.outgoingMinor, 'INR', 'DEBIT')}
                      </td>
                      <td className="statement__balance num" data-label="Balance">
                        {formatMinor(SPECIMEN.afterOutgoingMinor, 'INR')}
                      </td>
                    </tr>
                    <tr>
                      <th scope="row" className="statement__date">
                        <time dateTime="2026-11-08">08 Nov</time>
                      </th>
                      <td className="statement__particulars">
                        <span className="statement__primary">Transfer from K. Rao</span>
                        <span className="statement__ref mono">SP·CR·1214</span>
                      </td>
                      <td className="statement__amount statement__amount--credit num">
                        {formatSigned(SPECIMEN.incomingMinor, 'INR', 'CREDIT')}
                      </td>
                      <td className="statement__balance num" data-label="Balance">
                        {formatMinor(SPECIMEN.closingMinor, 'INR')}
                      </td>
                    </tr>
                  </tbody>
                </table>

                <footer className="statement__foot">
                  <div className="statement__closing">
                    <span>Closing balance</span>
                    <strong>{formatMinor(SPECIMEN.closingMinor, 'INR')}</strong>
                  </div>
                  <div className="statement__check">
                    <StatusMark ok />
                    <span>Stored balance equals journal sum</span>
                  </div>
                </footer>

                <p className="statement__fine">
                  Illustrative specimen. Not a real account or a record of money held.
                </p>
              </article>
            </figure>
          </div>
        </section>

        <section className="home-section" id="principles" aria-labelledby="principles-title">
          <div className="home-wrap">
            <header className="home-section__head">
              <div className="home-section__meta">
                <span className="home-section__index">01 / 04</span>
                <p className="folio">Principles</p>
              </div>
              <div>
                <h2 id="principles-title">A wallet that shows its working.</h2>
                <p className="home-section__lede">
                  Open an INR, USD or EUR account and every balance arrives with its own
                  statement. The number on top is not an assertion; it is the sum of the
                  entries beneath it.
                </p>
              </div>
            </header>

            <div className="principle-grid">
              {PRINCIPLES.map((principle) => (
                <article className="principle" key={principle.no}>
                  <span className="principle__no">{principle.no}</span>
                  <h3>{principle.title}</h3>
                  <p>{principle.body}</p>
                </article>
              ))}
            </div>

            <p className="home-statement-line">The account is a view. The journal is the record.</p>
          </div>
        </section>

        <section className="home-section" id="journal" aria-labelledby="journal-title">
          <div className="home-wrap">
            <header className="home-section__head">
              <div className="home-section__meta">
                <span className="home-section__index">02 / 04</span>
                <p className="folio">The journal</p>
              </div>
              <div>
                <h2 id="journal-title">A transfer is a pair of facts.</h2>
                <p className="home-section__lede">
                  {formatMinor(SPECIMEN.transferMinor, 'INR')} does not disappear from one
                  wallet and reappear in another. It is recorded as a debit and a credit, under
                  one transaction, in the same commit.
                </p>
              </div>
            </header>

            <div className="journal-layout">
              <article className="voucher" aria-labelledby="voucher-title">
                <header className="voucher__head">
                  <div>
                    <p className="folio">Journal voucher</p>
                    <h3 id="voucher-title">Transfer between customer accounts</h3>
                  </div>
                  <span className="specimen-stamp" aria-hidden="true">
                    Specimen
                  </span>
                </header>

                <dl className="voucher__meta">
                  <div>
                    <dt>Transaction</dt>
                    <dd className="mono">SP-TXN-8F2A-4C19</dd>
                  </div>
                  <div>
                    <dt>Posted</dt>
                    <dd>08 Nov 2026 · 18:42</dd>
                  </div>
                  <div>
                    <dt>Amount</dt>
                    <dd className="mono">{formatMinor(SPECIMEN.transferMinor, 'INR')}</dd>
                  </div>
                </dl>

                <div className="voucher__lines">
                  <div className="voucher__line voucher__line--debit">
                    <div className="voucher__party">
                      <span className="side side--debit">Debit</span>
                      <p>K. Rao · INR wallet</p>
                      <span className="voucher__account mono">SP-INR-0917</span>
                    </div>
                    <span className="voucher__amount">
                      {formatMinor(SPECIMEN.transferMinor, 'INR')}
                    </span>
                  </div>
                  <div className="voucher__line voucher__line--credit">
                    <div className="voucher__party">
                      <span className="side side--credit">Credit</span>
                      <p>R. Iyer · INR wallet</p>
                      <span className="voucher__account mono">SP-INR-2041</span>
                    </div>
                    <span className="voucher__amount">
                      {formatMinor(SPECIMEN.transferMinor, 'INR')}
                    </span>
                  </div>
                </div>

                <div className="voucher__total">
                  <div>
                    <span>Total debits</span>
                    <span className="mono">{formatMinor(SPECIMEN.transferMinor, 'INR')}</span>
                  </div>
                  <div>
                    <span>Total credits</span>
                    <span className="mono">{formatMinor(SPECIMEN.transferMinor, 'INR')}</span>
                  </div>
                  <div className="voucher__balanced">
                    <StatusMark ok />
                    <span>Difference {formatMinor(0, 'INR')}</span>
                  </div>
                </div>

                <p className="voucher__fine">
                  One transaction, two postings, one commit. Illustrative specimen.
                </p>
              </article>

              <div className="journal-notes">
                <ol className="journal-steps">
                  {JOURNAL_STEPS.map((step) => (
                    <li key={step.no}>
                      <span className="journal-steps__no">{step.no}</span>
                      <div>
                        <h3>{step.title}</h3>
                        <p>{step.body}</p>
                      </div>
                    </li>
                  ))}
                </ol>
                <p className="journal-note">
                  If either line fails, there is no half-transfer to repair. The pair fails
                  together, and the account balance stays where it was.
                </p>
              </div>
            </div>
          </div>
        </section>

        <section className="home-section" id="proof" aria-labelledby="proof-title">
          <div className="home-wrap">
            <header className="home-section__head">
              <div className="home-section__meta">
                <span className="home-section__index">03 / 04</span>
                <p className="folio">Proof</p>
              </div>
              <div>
                <h2 id="proof-title">The balance is checked, not trusted.</h2>
                <p className="home-section__lede">
                  Proof recomputes your account from its journal, compares the result with the
                  stored balance, and reports the difference. A certificate is only useful if
                  it can fail.
                </p>
              </div>
            </header>

            <div className="proof-layout">
              <article className="certificate" aria-labelledby="certificate-title">
                <header className="certificate__head">
                  <div>
                    <p className="folio">Certificate of balance</p>
                    <h3 id="certificate-title">This account is in balance.</h3>
                  </div>
                  <span className="specimen-stamp" aria-hidden="true">
                    Specimen
                  </span>
                </header>

                <p className="certificate__id mono">
                  SP-INR-2041 · checked 08 Nov 2026 · 18:42
                </p>

                <div className="certificate__compare">
                  <div>
                    <span>Stored balance</span>
                    <strong>{formatMinor(SPECIMEN.closingMinor, 'INR')}</strong>
                  </div>
                  <div>
                    <span>Journal balance</span>
                    <strong>{formatMinor(SPECIMEN.closingMinor, 'INR')}</strong>
                  </div>
                </div>

                <div className="certificate__result">
                  <StatusMark ok />
                  <div>
                    <p className="certificate__result-title">
                      Difference <span>{formatMinor(0, 'INR')}</span>
                    </p>
                    <p className="certificate__result-note">
                      Every linked transaction nets to zero.
                    </p>
                  </div>
                </div>

                <dl className="certificate__meta">
                  <div>
                    <dt>Entries</dt>
                    <dd>3</dd>
                  </div>
                  <div>
                    <dt>Transactions</dt>
                    <dd>Balanced</dd>
                  </div>
                  <div>
                    <dt>Currency</dt>
                    <dd>INR</dd>
                  </div>
                  <div>
                    <dt>Checked</dt>
                    <dd>08 Nov 2026</dd>
                  </div>
                </dl>

                <p className="certificate__fine">
                  Illustrative specimen. Live certificates are computed by the server from
                  posted entries.
                </p>
              </article>

              <div className="proof-notes">
                <h3>What the certificate compares</h3>
                <p>
                  The stored balance is fast to read. The journal is the evidence behind it.
                  Proof puts the two side by side so a mismatch cannot hide.
                </p>
                <dl className="proof-notes__list">
                  {PROOF_POINTS.map((point) => (
                    <div key={point.term}>
                      <dt>{point.term}</dt>
                      <dd>{point.detail}</dd>
                    </div>
                  ))}
                </dl>
                <p className="proof-notes__note">
                  No external auditor. No attestation. Just arithmetic the server can
                  reproduce and you can inspect.
                </p>
              </div>
            </div>
          </div>
        </section>

        <section className="home-band" id="engineering" aria-labelledby="engineering-title">
          <div className="home-wrap">
            <header className="home-band__head">
              <p className="folio">04 / 04 · Engineering</p>
              <h2 id="engineering-title">Built for the failure modes.</h2>
              <p className="home-band__lede">
                A ledger is easy to draw and hard to keep correct under concurrency, retries
                and crashes. SentinelPay is organized around the ways naive payment services
                break.
              </p>
            </header>

            <div className="band-grid">
              {ENGINEERING.map((item) => (
                <article key={item.no}>
                  <span className="band-grid__no">{item.no}</span>
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </article>
              ))}
            </div>

            <p className="band-colophon">
              Java 21 · Spring Boot 3.5 · PostgreSQL · Redis · Kafka · 100,000-record clearing
              files
            </p>
          </div>
        </section>

        <section className="home-cta" aria-labelledby="cta-title">
          <div className="home-wrap home-cta__inner">
            <div className="home-cta__copy">
              <p className="folio">Start here</p>
              <h2 id="cta-title">Open a ledger, not just an account.</h2>
              <p>
                Start with INR, USD or EUR. Every posting will be yours to inspect, and every
                balance will come with its arithmetic.
              </p>
            </div>

            <div className="home-cta__actions">
              <Link className="btn btn--primary" to={primaryTo}>
                {authenticated ? 'Open your wallet' : 'Open your ledger'}
              </Link>
              {!authenticated ? (
                <Link className="btn btn--ghost" to="/login">
                  Sign in to an existing ledger
                </Link>
              ) : null}
              <p className="home-cta__note">
                An engineering project, not a bank. Specimen figures, not real money.
              </p>
            </div>
          </div>
        </section>
      </main>

      <footer className="home-foot">
        <div className="home-wrap home-foot__inner">
          <div className="home-foot__brand">
            <Link className="brand-home" to="/">
              <Brand />
            </Link>
            <p>A double-entry wallet and settlement ledger, built as an engineering project.</p>
          </div>

          <nav className="home-foot__nav" aria-label="Footer">
            {NAV.map((item) => (
              <a key={item.href} href={item.href}>
                {item.label}
              </a>
            ))}
            {!authenticated ? (
              <Link to="/login">Sign in</Link>
            ) : (
              <Link to="/wallet">Open wallet</Link>
            )}
          </nav>

          <div className="home-foot__legal">
            <p>
              SentinelPay is not a bank, a deposit taker, or a payment institution. It does not
              hold customer funds. All names, account numbers, balances and certificates on
              this page are specimens.
            </p>
            <p className="home-foot__colophon">Java 21 · Spring Boot · PostgreSQL · Redis · Kafka</p>
          </div>
        </div>

        <div className="home-wrap home-foot__base">
          <span>SentinelPay · A double-entry ledger</span>
          <a href="#top">Back to top</a>
        </div>
      </footer>
    </div>
  );
}
