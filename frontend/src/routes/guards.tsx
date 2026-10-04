import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';

import { useAuth } from '../auth/auth-context';
import { LoadingState } from '../components';

/** Gate for any signed-in user; remembers where they were heading. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <LoadingState label="Comprobando sesion" rows={2} />;
  if (!user) return <Navigate to="/entrar" replace state={{ from: location.pathname }} />;

  // The seeded password is public, so nothing else is reachable until it changes.
  if (user.mustChangePassword) return <Navigate to="/cambiar-contrasena" replace />;

  return <>{children}</>;
}

/** Gate for one or more roles, e.g. <RequireRole roles={['ADMINISTRADOR']}>. */
export function RequireRole({
  roles,
  children,
}: {
  roles: string[];
  children: ReactNode;
}) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <LoadingState label="Comprobando permisos" rows={2} />;
  if (!user) return <Navigate to="/entrar" replace state={{ from: location.pathname }} />;
  if (!roles.includes(user.role)) return <Navigate to="/" replace />;

  return <>{children}</>;
}
