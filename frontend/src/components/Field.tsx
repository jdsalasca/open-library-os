import type {
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';
import { useId } from 'react';

import './Field.scss';

/** An input is always named: a visible label, or an aria-label when it is compact. */
type Labelled = { label: string; ariaLabel?: never };
type Unlabelled = { label?: never; ariaLabel: string };

type Base = (Labelled | Unlabelled) & {
  hint?: ReactNode;
  error?: string;
};

type TextProps = Base & InputHTMLAttributes<HTMLInputElement> & { as?: 'input' };
type AreaProps = Base & TextareaHTMLAttributes<HTMLTextAreaElement> & { as: 'textarea' };
type SelectProps = Base &
  SelectHTMLAttributes<HTMLSelectElement> & {
    as: 'select';
    options: Array<{ value: string; label: string }>;
  };

export type FieldProps = TextProps | AreaProps | SelectProps;

export function Field(props: FieldProps) {
  const { label, ariaLabel, hint, error, as = 'input' } = props;
  const id = useId();
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`]
    .filter(Boolean)
    .join(' ');

  const a11y = {
    id,
    className: 'field__control',
    'aria-label': label ? undefined : ariaLabel,
    'aria-invalid': error ? ('true' as const) : undefined,
    'aria-describedby': describedBy || undefined,
  };

  let control: ReactNode;
  if (as === 'textarea') {
    const { as: _as, label: _l, ariaLabel: _a, hint: _h, error: _e, ...rest } =
      props as AreaProps;
    control = <textarea {...rest} {...a11y} rows={rest.rows ?? 4} />;
  } else if (as === 'select') {
    const { as: _as, label: _l, ariaLabel: _a, hint: _h, error: _e, options, ...rest } =
      props as SelectProps;
    control = (
      <select {...rest} {...a11y}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  } else {
    const { as: _as, label: _l, ariaLabel: _a, hint: _h, error: _e, ...rest } =
      props as TextProps;
    control = <input {...rest} {...a11y} />;
  }

  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      {label && (
        <label className="field__label" htmlFor={id}>
          {label}
        </label>
      )}
      {control}
      {hint && (
        <p className="field__hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field__error" id={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  );
}