import { api, type Problem } from './client';

export type Role = 'LECTOR' | 'ADMINISTRATIVO' | 'BIBLIOTECARIO' | 'ADMINISTRADOR';

export const ROLES: Role[] = ['LECTOR', 'ADMINISTRATIVO', 'BIBLIOTECARIO', 'ADMINISTRADOR'];

export const ROLE_LABEL: Record<Role, string> = {
  LECTOR: 'Lector',
  BIBLIOTECARIO: 'Bibliotecario',
  ADMINISTRATIVO: 'Administrativo',
  ADMINISTRADOR: 'Administrador',
};

export type UserProfile = {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  active: boolean;
  mustChangePassword: boolean;
  authorities: string[];
};

/** Same shape as the profile; the list omits nothing, so one type is enough. */
export type UserSummary = UserProfile;

export function csrf() {
  return api.get<{ token: string; header: string }>('/auth/csrf');
}

export function login(email: string, password: string) {
  return api.post<UserProfile>('/auth/login', { email, password });
}

export function logout() {
  return api.post<void>('/auth/logout');
}

export function me() {
  return api.get<UserProfile>('/auth/me');
}

export function changePassword(currentPassword: string, newPassword: string) {
  return api.put<void>('/auth/password', { currentPassword, newPassword });
}

export function listUsers() {
  return api.get<UserSummary[]>('/users');
}

export function createUser(input: {
  email: string;
  fullName: string;
  role: Role;
  password: string;
}) {
  return api.post<UserSummary>('/users', input);
}

export function updateUser(id: number, input: { fullName?: string; active?: boolean }) {
  return api.put<UserSummary>(`/users/${id}`, input);
}

export function changeRole(id: number, role: Role) {
  return api.patch<UserSummary>(`/users/${id}/role`, { role });
}

export type { Problem };

/**
 * The roster a migrating library already has. Sent as text/csv because that is what
 * FileReader hands over; every account arrives as a reader that must choose its own
 * password on first login.
 */
export type ReaderImportReport = {
  created: number;
  alreadyThere: number;
  failed: number;
  problems: { line: number; reason: string }[];
};

export function importReaders(csv: string) {
  return api.postRaw<ReaderImportReport>('/users/import', csv, 'text/csv');
}