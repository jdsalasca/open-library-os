import { api } from './client';
import type { Page } from './catalog';

export type CopyStatus = 'DISPONIBLE' | 'PRESTADO' | 'MANTENIMIENTO' | 'PERDIDO';
export type LocationKind = 'SALA' | 'PASILLO' | 'ESTANTE' | 'DEPOSITO';

export const STATUS_LABEL: Record<CopyStatus, string> = {
  DISPONIBLE: 'Disponible',
  PRESTADO: 'Prestado',
  MANTENIMIENTO: 'Mantenimiento',
  PERDIDO: 'Perdido',
};

export const KIND_LABEL: Record<LocationKind, string> = {
  SALA: 'Sala',
  PASILLO: 'Pasillo',
  ESTANTE: 'Estante',
  DEPOSITO: 'Deposito',
};

/** Only these two hold physical copies. */
export const HOLDABLE_KINDS: LocationKind[] = ['ESTANTE', 'DEPOSITO'];

export type Copy = {
  id: number;
  code: string;
  barcode: string;
  qr: string;
  status: CopyStatus;
  bookId: number;
  bookTitle?: string;
  bookIsbn?: string;
  locationId?: number;
  locationCode?: string;
};

export type LocationNode = {
  id: number;
  code: string;
  name: string;
  kind: LocationKind;
  parentId?: number;
  /** Absent means not placed yet, which is not the same as standing at the origin. */
  x?: number;
  y?: number;
  z?: number;
  width?: number;
  depth?: number;
  height?: number;
  children: LocationNode[];
  copies: number;
};

export type BookStock = {
  bookId: number;
  title: string;
  subtitle?: string;
  authors: string[];
  isbn13?: string;
  copies: number;
  available: number;
};

export type MoveRecord = {
  id: number;
  fromCode?: string;
  toCode?: string;
  movedAt: string;
  movedBy?: string;
};

function queryString(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function listCopies(query: {
  q?: string;
  status?: CopyStatus;
  bookId?: number;
  locationId?: number;
  page?: number;
  size?: number;
}) {
  return api.get<Page<Copy>>(`/inventory/copies${queryString(query)}`);
}

export function addCopies(input: {
  bookId: number;
  quantity: number;
  locationId?: number;
  acquiredAt?: string;
}) {
  return api.post<{ requested: number; created: Copy[] }>('/inventory/copies/bulk', input);
}

export function moveCopy(id: number, toLocationId: number) {
  return api.post<Copy>(`/inventory/copies/${id}/move`, { toLocationId });
}

export function setCopyStatus(id: number, status: CopyStatus) {
  return api.patch<Copy>(`/inventory/copies/${id}/status`, { status });
}

export function copyMoves(id: number) {
  return api.get<MoveRecord[]>(`/inventory/copies/${id}/moves`);
}

/** Exact lookup by whatever a camera or a USB scanner produced. */
export function lookupCopy(code: string) {
  return api.get<Copy>(`/inventory/lookup${queryString({ code })}`);
}

export function labelUrl(id: number) {
  return `/api/inventory/copies/${id}/label.png`;
}

export function listLocations() {
  return api.get<LocationNode[]>('/inventory/locations');
}

export function createLocation(input: {
  code: string;
  name: string;
  kind: LocationKind;
  parentId?: number;
}) {
  return api.post<LocationNode>('/inventory/locations', input);
}

export function updateLocation(input: {
  id: number;
  code: string;
  name: string;
  kind: LocationKind;
  x?: number | null;
  z?: number | null;
  width?: number;
  depth?: number;
}) {
  return api.put<LocationNode>(`/inventory/locations/${input.id}`, input);
}

export function deleteLocation(id: number) {
  return api.delete<void>(`/inventory/locations/${id}`);
}

export function listStock() {
  return api.get<BookStock[]>('/inventory/books');
}

/** Flattens the tree so a <select> can list every shelf with its full path. */
export function flattenLocations(
  nodes: LocationNode[],
  parentPath = '',
): Array<LocationNode & { path: string }> {
  return nodes.flatMap((node) => {
    const path = parentPath ? `${parentPath} / ${node.code}` : node.code;
    return [{ ...node, path }, ...flattenLocations(node.children, path)];
  });
}
