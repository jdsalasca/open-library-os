import { describe, expect, it } from 'vitest';

import {
  HOLDABLE_KINDS,
  KIND_LABEL,
  STATUS_LABEL,
  flattenLocations,
  type CopyStatus,
  type LocationKind,
  type LocationNode,
} from '../api/inventory';

describe('etiquetas de inventario', () => {
  it('cubre todos los estados y todos los tipos de ubicacion', () => {
    // The UI maps these by key; a new enum value without a label would show "undefined".
    const statuses: CopyStatus[] = ['DISPONIBLE', 'PRESTADO', 'MANTENIMIENTO', 'PERDIDO'];
    const kinds: LocationKind[] = ['SALA', 'PASILLO', 'ESTANTE', 'DEPOSITO'];

    expect(Object.keys(STATUS_LABEL).sort()).toEqual([...statuses].sort());
    expect(Object.keys(KIND_LABEL).sort()).toEqual([...kinds].sort());
    for (const status of statuses) expect(STATUS_LABEL[status]).toBeTruthy();
    for (const kind of kinds) expect(KIND_LABEL[kind]).toBeTruthy();
  });

  it('solo los estantes y depositos guardan ejemplares', () => {
    expect(HOLDABLE_KINDS).toEqual(['ESTANTE', 'DEPOSITO']);
    expect(HOLDABLE_KINDS).not.toContain('PASILLO');
    expect(HOLDABLE_KINDS).not.toContain('SALA');
  });
});

describe('flattenLocations', () => {
  const tree: LocationNode[] = [
    {
      id: 1,
      code: 'SALA-1',
      name: 'Sala principal',
      kind: 'SALA',
      children: [
        {
          id: 2,
          code: 'P-1',
          name: 'Pasillo 1',
          kind: 'PASILLO',
          children: [
            { id: 3, code: 'E-1', name: 'Estante 1', kind: 'ESTANTE', children: [], copies: 4 },
          ],
          copies: 4,
        },
      ],
      copies: 0,
    },
  ];

  it('flattens the tree keeping the full path', () => {
    expect(flattenLocations(tree).map((node) => node.path)).toEqual([
      'SALA-1',
      'SALA-1 / P-1',
      'SALA-1 / P-1 / E-1',
    ]);
  });

  it('keeps the copy count of each node', () => {
    expect(flattenLocations(tree).find((node) => node.code === 'E-1')?.copies).toBe(4);
  });

  it('returns nothing for an empty tree', () => {
    expect(flattenLocations([])).toEqual([]);
  });
});
