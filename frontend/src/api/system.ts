import { api } from './client';

export type Health = {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE';
  components?: Record<string, { status: string }>;
};

export function getHealth(signal?: AbortSignal) {
  return api.get<Health>('/actuator/health', signal);
}