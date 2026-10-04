import type { ReactNode } from 'react';

import './Badge.scss';

export type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info';

type BadgeProps = {
  children: ReactNode;
  tone?: Tone;
  /** Renders a small dot before the label (status semantics). */
  dot?: boolean;
};

export function Badge({ children, tone = 'neutral', dot }: BadgeProps) {
  return (
    <span className={`badge badge--${tone}`}>
      {dot && <span className="badge__dot" aria-hidden="true" />}
      {children}
    </span>
  );
}