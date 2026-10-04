import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';

import './Button.scss';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md' | 'lg';

type CommonProps = {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  iconStart?: ReactNode;
  iconEnd?: ReactNode;
  fullWidth?: boolean;
};

type ButtonAsButton = CommonProps &
  ButtonHTMLAttributes<HTMLButtonElement> & { to?: undefined };

type ButtonAsLink = CommonProps & {
  to: string;
  children: ReactNode;
  className?: string;
};

export type ButtonProps = ButtonAsButton | ButtonAsLink;

function classes(props: ButtonAsButton): string {
  const { variant = 'primary', size = 'md', fullWidth, className } = props;
  return [
    'btn',
    `btn--${variant}`,
    `btn--${size}`,
    fullWidth && 'btn--full',
    className,
  ]
    .filter(Boolean)
    .join(' ');
}

export function Button(props: ButtonProps) {
  if ('to' in props && props.to) {
    const { to, children, variant, size, fullWidth, iconStart, iconEnd } = props;
    return (
      <Link className={classes({ ...(props as object), variant, size, fullWidth })} to={to}>
        {iconStart}
        <span>{children}</span>
        {iconEnd}
      </Link>
    );
  }

  const {
    children,
    variant = 'primary',
    size = 'md',
    loading = false,
    fullWidth,
    iconStart,
    iconEnd,
    className,
    disabled,
    ...rest
  } = props as ButtonAsButton;

  return (
    <button
      {...rest}
      type={rest.type ?? 'button'}
      className={classes({ ...rest, variant, size, fullWidth, className })}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
    >
      {loading && <span className="btn__spinner" aria-hidden="true" />}
      {!loading && iconStart}
      <span>{children}</span>
      {!loading && iconEnd}
    </button>
  );
}