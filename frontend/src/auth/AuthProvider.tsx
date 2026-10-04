import { useMemo, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import * as auth from '../api/users';
import { AuthContext, type AuthValue } from './auth-context';

/**
 * The session lives in a Postgres-backed cookie, so the browser only needs to know
 * who it is. `csrf()` runs once up front to make sure the cookie exists before
 * the first write.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  const session = useQuery({
    queryKey: ['me'],
    queryFn: async () => {
      await auth.csrf();
      try {
        return await auth.me();
      } catch {
        return null;
      }
    },
    staleTime: 60_000,
    retry: false,
  });

  const loginMutation = useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) =>
      // Fetch a fresh CSRF cookie first: one left over from a previous session
      // would be rejected.
      auth.csrf().then(() => auth.login(email, password)),
    onSuccess: (user) => queryClient.setQueryData(['me'], user),
  });

  const logoutMutation = useMutation({
    mutationFn: () => auth.logout(),
    onSuccess: () => {
      // Drop everything cached for the old session, but keep the ['me'] entry
      // alive so the query object is not torn down mid-flight.
      queryClient.setQueryData(['me'], null);
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'me' });
    },
  });

  const value = useMemo<AuthValue>(() => {
    const user = session.data ?? null;
    const authorities = new Set(user?.authorities ?? []);

    return {
      user,
      loading: session.isPending,
      can: (...needed) => needed.every((a) => authorities.has(a)),
      hasRole: (...roles) => (user ? roles.includes(user.role) : false),
      login: (email, password) => loginMutation.mutateAsync({ email, password }),
      logout: () => logoutMutation.mutateAsync(),
      refresh: async () => {
        await queryClient.invalidateQueries({ queryKey: ['me'] });
      },
    };
  }, [session.data, session.isPending, loginMutation, logoutMutation, queryClient]);

  return <AuthContext value={value}>{children}</AuthContext>;
}
