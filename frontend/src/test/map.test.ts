import { describe, expect, it } from 'vitest';

import {
  KIND_LABEL,
  METRES_PER_PIXEL,
  booksOn,
  childrenOf,
  fetchMap,
  holdsCopies,
  pathTo,
  planSize,
  project,
  type MapNode,
  type MapView,
} from '../api/map';

const node = (over: Partial<MapNode>): MapNode => ({
  id: 1,
  code: 'E-1',
  name: 'Estante 1',
  kind: 'ESTANTE',
  x: 0,
  y: 0,
  z: 0,
  width: 2,
  depth: 1,
  height: 2,
  copies: 0,
  occupancy: {},
  items: [],
  ...over,
});

const view: MapView = {
  bounds: { minX: 0, maxX: 10, minZ: 0, maxZ: 8, width: 10, depth: 8 },
  nodes: [
    node({ id: 1, code: 'SALA-1', name: 'Sala', kind: 'SALA', width: 10, depth: 8 }),
    node({ id: 2, code: 'P-1', name: 'Pasillo 1', kind: 'PASILLO', parentId: 1, z: 2 }),
    node({ id: 3, code: 'E-1', name: 'Estante 1', kind: 'ESTANTE', parentId: 2, z: 3 }),
    node({ id: 4, code: 'DEP-1', name: 'Deposito', kind: 'DEPOSITO', z: 6 }),
  ],
};

describe('el plano', () => {
  it('proyecta metros a pixeles usando el origen de la biblioteca', () => {
    const places = project(view);

    const shelf = places.find((place) => place.node.code === 'E-1');
    // The shelf sits at x=0 and z=3 in the fixture: only the depth moves it.
    expect(shelf?.left).toBe(0);
    expect(shelf?.top).toBe(3 * METRES_PER_PIXEL);
    expect(shelf?.w).toBe(2 * METRES_PER_PIXEL);
  });

  it('nunca dibuja una caja de cero pixeles', () => {
    // Un estante sin medir viene con medidas de relleno; si esas llegaran a cero
    // el nodo seria invisible y el mapa pareceria vacio.
    const flat = project({ ...view, nodes: [node({ width: 0, depth: 0, height: 0 })] });

    expect(flat[0].w).toBeGreaterThan(0);
    expect(flat[0].d).toBeGreaterThan(0);
    expect(flat[0].h).toBeGreaterThan(0);
  });

  it('ordena por profundidad para que el fondo se vea mas apagado', () => {
    const ratios = project(view).map((place) => place.depthRatio);

    expect(ratios[0]).toBe(0);
    expect(Math.max(...ratios)).toBeLessThanOrEqual(1);
    expect(ratios.find((_, index) => view.nodes[index].code === 'E-1')).toBeCloseTo(3 / 8);
  });

  it('el tamano del plano sale de los limites con un suelo utilizable', () => {
    const size = planSize(view);

    // The floor keeps a one-shelf library from rendering in a postage stamp.
    expect(size.width).toBe(Math.max(10 * METRES_PER_PIXEL, 240));
    expect(size.depth).toBe(Math.max(8 * METRES_PER_PIXEL, 200));
  });

  it('un plano de un solo nodo no colapsa a cero', () => {
    const tiny: MapView = {
      bounds: { minX: 3, maxX: 3, minZ: 4, maxZ: 4, width: 1, depth: 1 },
      nodes: [node({ x: 3, z: 4 })],
    };

    expect(planSize(tiny).width).toBeGreaterThan(0);
    expect(planSize(tiny).depth).toBeGreaterThan(0);
  });
});

describe('la ruta hasta un estante', () => {
  it('encadena sala, pasillo y estante en orden de entrada', () => {
    const shelf = view.nodes.find((candidate) => candidate.code === 'E-1')!;

    expect(pathTo(shelf, view.nodes).map((step) => step.code)).toEqual([
      'SALA-1',
      'P-1',
      'E-1',
    ]);
  });

  it('una raiz no tiene padre y se resuelve sola', () => {
    const root = view.nodes.find((candidate) => candidate.code === 'SALA-1')!;

    expect(pathTo(root, view.nodes).map((step) => step.code)).toEqual(['SALA-1']);
  });

  it('un ciclo en los datos no cuelga la aplicacion', () => {
    const loop = [
      node({ id: 1, code: 'A', parentId: 2 }),
      node({ id: 2, code: 'B', parentId: 1 }),
    ];

    // pathTo walks parents; with a cycle it must stop rather than spin forever.
    expect(pathTo(loop[0], loop).length).toBeLessThanOrEqual(loop.length);
  });

  it('lista los hijos y, sin nodo, las raices', () => {
    const shelf = view.nodes.find((candidate) => candidate.code === 'P-1')!;

    expect(childrenOf(undefined, view.nodes).map((n) => n.code)).toEqual(['SALA-1', 'DEP-1']);
    expect(childrenOf(shelf, view.nodes).map((n) => n.code)).toEqual(['E-1']);
  });
});

describe('los libros de un estante', () => {
  const stocked = node({
    items: [
      { copyId: 1, code: 'OL-1', status: 'DISPONIBLE', bookId: 7, bookTitle: 'Ficciones' },
      { copyId: 2, code: 'OL-2', status: 'PRESTADO', bookId: 7, bookTitle: 'Ficciones' },
      { copyId: 3, code: 'OL-3', status: 'DISPONIBLE', bookId: 8, bookTitle: 'Rayuela' },
    ],
  });

  it('agrupa por libro y cuenta los disponibles', () => {
    const books = booksOn(stocked);

    expect(books).toHaveLength(2);
    const fiction = books.find((book) => book.bookId === 7);
    expect(fiction).toMatchObject({ title: 'Ficciones', copies: 2, available: 1 });
  });

  it('un estante vacio no inventa libros', () => {
    expect(booksOn(node({}))).toEqual([]);
    expect(booksOn(undefined)).toEqual([]);
  });

  it('solo estantes y depositos guardan ejemplares', () => {
    expect(holdsCopies('ESTANTE')).toBe(true);
    expect(holdsCopies('DEPOSITO')).toBe(true);
    expect(holdsCopies('SALA')).toBe(false);
    expect(holdsCopies('PASILLO')).toBe(false);
  });

  it('tiene nombre para cada tipo de ubicacion del backend', () => {
    expect(Object.keys(KIND_LABEL).sort()).toEqual(['DEPOSITO', 'ESTANTE', 'PASILLO', 'SALA']);
  });

  it('pide el plano al endpoint unico del mapa', () => {
    expect(fetchMap).toBeTypeOf('function');
  });
});
