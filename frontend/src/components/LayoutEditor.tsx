import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { listLocations, updateLocation, type LocationNode } from '../api/inventory';
import { Button, Card, CardBody, CardHeader, EmptyState, Field, Skeleton } from './index';
import './layout-editor.scss';

/**
 * Where each shelf stands.
 *
 * The 3D map draws the geometry the database holds, and until now there was no
 * way to put it there from the browser: a fresh install got an empty map. The
 * coordinates are metres, with the origin in one corner of the room, which is
 * what the map already assumes.
 */
const MAX_METRES = 1000;

export function LayoutEditor() {
  const queryClient = useQueryClient();
  const locations = useQuery({ queryKey: ['locations'], queryFn: listLocations });

  const shelves = flatten(locations.data ?? []).filter((node) => node.kind === 'ESTANTE');
  const [chosen, setChosen] = useState<LocationNode | null>(null);
  const [draft, setDraft] = useState({ x: '', z: '' });

  const save = useMutation({
    mutationFn: updateLocation,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['locations'] });
    },
  });

  if (locations.isPending) {
    return <Skeleton height="6rem" />;
  }

  const pick = (node: LocationNode) => {
    setChosen(node);
    setDraft({ x: String(node.x ?? ''), z: String(node.z ?? '') });
  };

  const number = (text: string) => (text === '' ? undefined : Number(text));
  const outOfRange = [draft.x, draft.z].some(
    (v) => v !== '' && (Math.abs(Number(v)) > MAX_METRES || Number.isNaN(Number(v))),
  );

  const submit = () => {
    if (!chosen || outOfRange) return;
    save.mutate({
      id: chosen.id,
      code: chosen.code,
      name: chosen.name,
      kind: chosen.kind,
      x: number(draft.x) ?? null,
      z: number(draft.z) ?? null,
      width: chosen.width,
      depth: chosen.depth,
    });
  };

  return (
    <Card>
      <CardHeader
        title="Donde esta cada estanteria"
        subtitle="Metros desde la esquina de la sala. El mapa usa estas coordenadas."
      />
      <CardBody>
        {shelves.length === 0 ? (
          <EmptyState
            title="Todavia no hay nada colocado"
            description="Crea una estanteria en Inventario y despues situ aqui en el mapa."
            icon="map"
          />
        ) : (
          <>
            <ul className="layout__list">
              {shelves.map((node) => (
                <li key={node.id}>
                  <button
                    type="button"
                    className="layout__pick"
                    onClick={() => pick(node)}
                    aria-pressed={chosen?.id === node.id}
                  >
                    <span className="layout__name">{node.name}</span>
                    <span className="layout__where">
                      {node.x == null ? 'sin colocar' : `x ${node.x} · z ${node.z ?? 0}`}
                    </span>
                    <span className="layout__copies">{node.copies} ejemplares</span>
                  </button>
                </li>
              ))}
            </ul>

            {chosen && (
              <div className="layout__form">
                <div className="layout__grid">
                  <Field
                    label="X"
                    type="number"
                    step={0.05}
                    min={-MAX_METRES}
                    max={MAX_METRES}
                    value={draft.x}
                    onChange={(e) => setDraft({ ...draft, x: e.target.value })}
                    hint="Metros a lo largo de la sala"
                  />
                  <Field
                    label="Z"
                    type="number"
                    step={0.05}
                    min={-MAX_METRES}
                    max={MAX_METRES}
                    value={draft.z}
                    onChange={(e) => setDraft({ ...draft, z: e.target.value })}
                    hint="Metros de fondo"
                  />
                </div>
                <Button loading={save.isPending} disabled={outOfRange} onClick={submit}>
                  Guardar posicion
                </Button>
                {outOfRange && (
                  <p className="layout__error" role="alert">
                    Entre -{MAX_METRES} y {MAX_METRES} metros. Si el numero es otro, no es una
                    coordenada.
                  </p>
                )}
                {save.isError && (
                  <p className="layout__error" role="alert">
                    {(save.error as Error).message}
                  </p>
                )}
                {save.isSuccess && (
                  <p className="layout__ok" role="status">
                    Posicion guardada.
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </CardBody>
    </Card>
  );
}

function flatten(nodes: LocationNode[]): LocationNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children ?? [])]);
}