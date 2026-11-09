import type { ReactNode } from 'react';

export function PageHeader({
  folio,
  title,
  lede,
  actions,
}: {
  folio: string;
  title: ReactNode;
  lede?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-head">
      <div className="page-head__copy">
        <p className="folio">{folio}</p>
        <h1>{title}</h1>
        {lede ? <p className="page-head__lede">{lede}</p> : null}
      </div>
      {actions ? <div className="page-head__actions">{actions}</div> : null}
    </header>
  );
}
