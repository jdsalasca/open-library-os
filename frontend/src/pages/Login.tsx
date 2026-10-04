import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';

import { Button, Card, CardBody, ErrorState, Field } from '../components';
import { useAuth } from '../auth/auth-context';
import { ApiError } from '../api/client';
import './Login.scss';

export function Login() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  if (user) {
    return <Navigate to="/" replace />;
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password);
      const from = (location.state as { from?: string } | null)?.from ?? '/';
      navigate(from, { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, { detail: 'No se ha podido iniciar sesion.' }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <Card className="login__card">
        <CardBody>
          <div className="login__brand">
            <span className="login__mark" aria-hidden="true" />
            <div>
              <h1 className="login__title">Open Library OS</h1>
              <p className="login__lead">Gestion de librerias autoalojada</p>
            </div>
          </div>

          {error && (
            <div className="login__error">
              <ErrorState
                title="No hemos podido entrar"
                message={error.message}
              />
            </div>
          )}

          <form className="login__form" onSubmit={onSubmit} noValidate>
            <Field
              label="Correo"
              type="email"
              name="email"
              autoComplete="username"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <Field
              label="Contrasena"
              type="password"
              name="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <Button type="submit" fullWidth size="lg" loading={busy}>
              Entrar
            </Button>
          </form>

          <p className="login__hint">
            Primer arranque? Usa <code>admin@local</code> con la contrasena inicial; el sistema te
            pedira cambiarla.
          </p>
        </CardBody>
      </Card>
    </div>
  );
}