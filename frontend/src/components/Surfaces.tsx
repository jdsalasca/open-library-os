import type { ReactNode } from 'react';

import './Surfaces.scss';

type CardProps = {
  children: ReactNode;
  /** Removes body padding (for flush tables/media). */
  flush?: boolean;
  raised?: boolean;
  className?: string;
};

export function Card({ children, flush, raised, className }: CardProps) {
  const classes = ['card', flush && 'card--flush', raised && 'card--raised', className]
    .filter(Boolean)
    .join(' ');

  return <div className={classes}>{children}</div>;
}

type SectionProps = {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
};

type PageHeadProps = Omit<SectionProps, 'children' | 'title'> & {
  eyebrow?: ReactNode;
  title: ReactNode;
  lead?: ReactNode;
};

export function CardHeader({ title, subtitle, actions }: Omit<SectionProps, 'children'>) {
  return (
    <div className="card__header">
      <div>
        {title && <h2 className="card__title">{title}</h2>}
        {subtitle && <p className="card__subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-head__actions">{actions}</div>}
    </div>
  );
}

export function CardBody({ children }: { children: ReactNode }) {
  return <div className="card__body">{children}</div>;
}

export function CardFooter({ children }: { children: ReactNode }) {
  return <div className="card__footer">{children}</div>;
}

export function PageHead({ eyebrow, title, lead, actions }: PageHeadProps) {
  return (
    <header className="page-head">
      <div>
        {eyebrow && <p className="page-head__eyebrow">{eyebrow}</p>}
        {title && <h1 className="page-head__title">{title}</h1>}
        {lead && <p className="page-head__lead">{lead}</p>}
      </div>
      {actions && <div className="page-head__actions">{actions}</div>}
    </header>
  );
}