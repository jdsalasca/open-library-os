import { useState } from 'react';

import { Button, Card, CardBody, CardHeader, Field, PageHead } from '../components';
import { useAuth } from '../auth/auth-context';
import { ApiError } from '../api/client';
import './ChangePassword.scss';

/**
 * Shown while the seeded password is still in place. Until this succeeds the API
 * refuses everything else, so this is a gate rather than a preference.
 */
export function ChangePassword({ mandatory = false }: { mandatory?: boolean }) {
  const { user, refresh, logout } = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const mismatch = repeat.length > 0 && next !== repeat;

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (mismatch) return;
    setBusy(true);
    setError(null);
    try {
      const { changePassword } = await import('../api/users');
      await changePassword(current, next);
      setDone(true);
      setCurrent('');
      setNext('');
      setRepeat('');
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, { detail: 'No se ha podido cambiar la contrasena.' }));
    } finally {
      setBusy(false);
    }
  }

  if (!user) return null;

  if (done && !mandatory) {
    return (
      <Card>
        <CardBody>
          <p className="change__done">Tu contrasena se ha actualizado.</p>
          <Button variant="secondary" size="sm" onClick={() => setDone(false)}>
            Cambiar de nuevo
          </Button>
        </CardBody>
      </Card>
    );
  }

  return (
    <div className="change">
      <PageHead
        eyebrow={mandatory ? 'Acceso restringido' : 'Seguridad'}
        title={mandatory ? 'Cambia tu contrasena' : 'Cambiar contrasena'}
        lead={
          mandatory
            ? 'La contrasena inicial es publica. Elige una nueva para usar el resto de la aplicacion.'
            : 'Usa al menos 10 caracteres.'
        }
        actions={
          mandatory ? undefined : (
            <Button variant="ghost" size="sm" onClick={() => void logout()}>
              Cerrar sesion
            </Button>
          )
        }
      />

      <Card>
        <CardHeader title="Nueva contrasena" subtitle="Se aplica en el acto" />
        <CardBody>
          <form className="change__form" onSubmit={onSubmit} noValidate>
            <Field
              label="Contrasena actual"
              type="password"
              autoComplete="current-password"
              required
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
            />
            <Field
              label="Nueva contrasena"
              type="password"
              autoComplete="new-password"
              required
              hint="Minimo 10 caracteres."
              value={next}
              onChange={(e) => setNext(e.target.value)}
            />
            <Field
              label="Repite la nueva contrasena"
              type="password"
              autoComplete="new-password"
              required
              value={repeat}
              error={mismatch ? 'Las contrasenas no coinciden.' : undefined}
              onChange={(e) => setRepeat(e.target.value)}
            />

            {error && (
              <p className="change__error" role="alert">
                {error.message}
                {error.fieldError('newPassword') && `: ${error.fieldError('newPassword')}`}
              </p>
            )}

            <Button type="submit" loading={busy} disabled={mismatch || next.length < 10}>
              Guardar contrasena
            </Button>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}