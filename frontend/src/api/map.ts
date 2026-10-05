import { api } from './client';

export type MapKind = 'SALA' | 'PASILLO' | 'ESTANTE' | 'DEPOSITO';

export type MapItem = {
  copyId: number;
  code: string;
  barcode?: string;
  status: string;
  bookId: number;
  bookTitle?: string;
};

export type MapNode = {
  id: number;
  code: string;
  name: string;
  kind: MapKind;
  parentId?: number;
  x: number;
  y: number;
  z: number;
  width: number;
  depth: number;
  height: number;
  copies: number;
  occupancy: Record<string, number>;
  items: MapItem[];
};

export type MapView = {
  bounds: {
    minX: number;
    maxX: number;
    minZ: number;
    maxZ: number;
    width: number;
    depth: number;
  };
  nodes: MapNode[];
};

export function fetchMap() {
  return api.get<MapView>('/map');
}

export const KIND_LABEL: Record<MapKind, string> = {
  SALA: 'Sala',
  PASILLO: 'Pasillo',
  ESTANTE: 'Estante',
  DEPOSITO: 'Deposito',
};

/**
 * Pixels per metre. A 20 x 10 m room lands at 440 x 220 px, which fits the stage
 * on a laptop; anything smaller read as a diamond floating in an empty box.
 */
export const METRES_PER_PIXEL = 22;

export type Placement = {
  node: MapNode;
  /** Left/top in the projected plane, in pixels. */
  left: number;
  top: number;
  w: number;
  d: number;
  h: number;
  /** 0 = back of the room, 1 = nearest the reader. */
  depthRatio: number;
  /** Levels from the root, used to stack children above what holds them. */
  level: number;
  /**
   * How far toward the viewer this node is lifted, in pixels. NOT the node height:
   * a 3.2 m room would then float in front of the 1.9 m shelves standing on it.
   */
  lift: number;
};

/** How deep each node sits in the location tree, guarding against parent cycles. */
export function levels(nodes: MapNode[]): Map<number, number> {
  const byId = new Map(nodes.map((candidate) => [candidate.id, candidate]));
  const result = new Map<number, number>();
  for (const node of nodes) {
    let level = 0;
    let cursor = node;
    const seen = new Set<number>([node.id]);
    while (cursor.parentId !== undefined && !seen.has(cursor.parentId)) {
      seen.add(cursor.parentId);
      const parent = byId.get(cursor.parentId);
      if (!parent) break;
      level += 1;
      cursor = parent;
    }
    result.set(node.id, level);
  }
  return result;
}

/**
 * Projects the floor plan into a flat plane. The perspective itself is CSS, so the
 * maths here only has to turn metres into pixels and remember which nodes are far
 * away, which is what decides the dimming.
 */
export function project(view: MapView, metresPerPixel = METRES_PER_PIXEL): Placement[] {
  const { minX, minZ, width, depth: depthMetres } = view.bounds;
  const nodeLevels = levels(view.nodes);
  return view.nodes.map((node) => ({
    node,
    left: (node.x - minX) * metresPerPixel,
    top: (node.z - minZ) * metresPerPixel,
    // Floors sized for a thumb: a 0.7 m shelf is 8 px at scale, and the plane's
    // tilt foreshortens it further, which made shelves untappable on a phone.
    w: Math.max(node.width * metresPerPixel, 44),
    d: Math.max(node.depth * metresPerPixel, 40),
    h: Math.max(node.height * metresPerPixel, 18),
    depthRatio: width > 0 && depthMetres > 0 ? (node.z - minZ) / depthMetres : 0,
    level: nodeLevels.get(node.id) ?? 0,
    // A few pixels per level: enough for a shelf to read as standing on its aisle.
    lift: (nodeLevels.get(node.id) ?? 0) * 10,
  }));
}

export function planSize(view: MapView, metresPerPixel = METRES_PER_PIXEL) {
  return {
    width: Math.max(view.bounds.width * metresPerPixel, 240),
    depth: Math.max(view.bounds.depth * metresPerPixel, 200),
  };
}

/** The chain a reader walks: room, then aisle, then shelf, then book. */
export function pathTo(node: MapNode, nodes: MapNode[]): MapNode[] {
  const byId = new Map(nodes.map((candidate) => [candidate.id, candidate]));
  const chain: MapNode[] = [];
  // A restored or hand-edited database can hold a parent cycle even though the API
  // refuses to create one; without the guard this loop never ends and the tab hangs.
  const seen = new Set<number>();
  let cursor: MapNode | undefined = node;
  while (cursor && !seen.has(cursor.id)) {
    seen.add(cursor.id);
    chain.unshift(cursor);
    cursor = cursor.parentId === undefined ? undefined : byId.get(cursor.parentId);
  }
  return chain;
}

export function childrenOf(node: MapNode | undefined, nodes: MapNode[]): MapNode[] {
  if (!node) return nodes.filter((candidate) => candidate.parentId === undefined);
  return nodes.filter((candidate) => candidate.parentId === node.id);
}

/** Books on a shelf, grouped so the list does not repeat the title per copy. */
export function booksOn(node: MapNode | undefined) {
  if (!node) return [];
  const groups = new Map<number, { bookId: number; title: string; copies: number; available: number }>();
  for (const item of node.items) {
    const group = groups.get(item.bookId) ?? {
      bookId: item.bookId,
      title: item.bookTitle ?? `Libro ${item.bookId}`,
      copies: 0,
      available: 0,
    };
    group.copies += 1;
    if (item.status === 'DISPONIBLE') group.available += 1;
    groups.set(item.bookId, group);
  }
  return [...groups.values()].sort((a, b) => a.title.localeCompare(b.title, 'es'));
}

/** Only shelves and depots actually hold copies; rooms are for the eye. */
export function holdsCopies(kind: MapKind): boolean {
  return kind === 'ESTANTE' || kind === 'DEPOSITO';
}
