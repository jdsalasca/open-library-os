import type { ReactNode } from 'react';

import { Button } from './Button';
import { Icon, type IconName } from './Icon';
import './States.scss';

type EmptyProps = {
  title: string;
  description?: ReactNode;
  action?: { label: string; to: string };
  icon?: IconName;
};

/** Shown when a list has no items. Never show a spinner instead. */
export function EmptyState({ title, description, action, icon = 'inbox' }: EmptyProps) {
  return (
    <div className="state state--empty">
      <span className="state__icon">
        <Icon name={icon} size={22} />
      </span>
      <h3 className="state__title">{title}</h3>
      {description && <p className="state__description">{description}</p>}
      {action && (
        <Button variant="secondary" size="sm" to={action.to}>
          {action.label}
        </Button>
      )}
    </div>
  );
}

type ErrorProps = {
  title?: string;
  /** Safe to render raw: always plain text or a sanitised node. */
  message?: string;
  onRetry?: () => void;
};

export function ErrorState({
  title = 'Algo ha ido mal',
  message = 'No hemos podido cargar esta informacion.',
  onRetry,
}: ErrorProps) {
  return (
    <div className="state state--error" role="alert">
      <span className="state__icon">
        <Icon name="alert" size={22} />
      </span>
      <h3 className="state__title">{title}</h3>
      <p className="state__description">{message}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Reintentar
        </Button>
      )}
    </div>
  );
}

export function Skeleton({
  width = '100%',
  height = '1rem',
  radius = 'var(--radius-sm)',
}: {
  width?: string;
  height?: string;
  radius?: string;
}) {
  return (
    <span
      className="skeleton"
      style={{ width, height, borderRadius: radius }}
      aria-hidden="true"
    />
  );
}

/** Generic loading placeholder with an accessible status message. */
export function LoadingState({ label = 'Cargando…', rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div className="state state--loading" role="status" aria-live="polite">
      <span className="visually-hidden">{label}</span>
      <div className="state__skeletons" aria-hidden="true">
        {Array.from({ length: rows }, (_, i) => (
          <Skeleton key={i} width={i === rows - 1 ? '60%' : '100%'} />
        ))}
      </div>
    </div>
  );
}