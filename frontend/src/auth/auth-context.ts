import { createContext, useContext } from 'react';

import type { Role, UserProfile } from '../api/users';

export type AuthValue = {
  user: UserProfile | null;
  loading: boolean;
  /** True when the session holds every listed authority. */
  can: (...authorities: string[]) => boolean;
  hasRole: (...roles: Role[]) => boolean;
  login: (email: string, password: string) => Promise<UserProfile>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
};

export const AuthContext = createContext<AuthValue | null>(null);

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
