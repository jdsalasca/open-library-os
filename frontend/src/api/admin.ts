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
