import { api } from './client';

export type Health = {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE';
  components?: Record<string, { status: string }>;
};

export interface LibraryProfile {
  name: string;
}

/** The name of this library, readable before signing in: the login screen needs it. */
export function libraryProfile(signal?: AbortSignal) {
  return api.get<LibraryProfile>('/system/library', signal);
}

export function getHealth(signal?: AbortSignal) {
  return api.get<Health>('/actuator/health', signal);
}