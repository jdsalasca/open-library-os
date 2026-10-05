import { api } from './client';

export type ImportReport = {
  created: Record<string, number>;
  updated: Record<string, number>;
  skipped: Record<string, number>;
};

export type LibraryDocument = {
  schemaVersion: number;
  exportedAt: string;
  counts: Record<string, number>;
};

export type BackupDump = {
  name: string;
  bytes: number;
};

export type BackupStatus = {
  healthy: boolean;
  /** Already worded for a person: the backend decides, the screen only shows. */
  message: string;
  lastRun?: number;
  hoursSinceLast: number;
  dumps: number;
  newest?: BackupDump;
};

/** What the backup container has been doing. Read-only: it never writes here. */
export function backupStatus() {
  return api.get<BackupStatus>('/backup/status');
}
