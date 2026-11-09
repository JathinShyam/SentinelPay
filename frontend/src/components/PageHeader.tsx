import type { ReactNode } from 'react';

export function PageHeader({
  folio,
  meta,
  title,
  lede,
  actions,
}: {
  folio: string;
  meta?: ReactNode;
  title: ReactNode;
  lede?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-head">
      <div className="page-head__meta">
        <p className="page-head__folio">{folio}</p>
        {meta ? <p className="page-head__meta-note">{meta}</p> : null}
      </div>
      <div className="page-head__main">
        <h1>{title}</h1>
        {lede || actions ? (
          <div className="page-head__foot">
            {lede ? <p className="page-head__lede">{lede}</p> : <span />}
            {actions ? <div className="page-head__actions">{actions}</div> : null}
          </div>
        ) : null}
      </div>
    </header>
  );
}
