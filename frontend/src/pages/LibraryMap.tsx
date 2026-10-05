import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHead,
} from '../components';
import {
  KIND_LABEL,
  booksOn,
  childrenOf,
  fetchMap,
  holdsCopies,
  pathTo,
  planSize,
  project,
  type MapNode,
} from '../api/map';
import { useScanner } from '../hooks/useScanner';
import './LibraryMap.scss';

/**
 * Where a book is, from a phone. The 3D-looking view is CSS perspective over the
 * same flat plan the accessible list below describes, so there is no WebGL to fail,
 * no second representation to keep in sync, and it works with motion turned off.
 */
export function LibraryMap() {
  const [selected, setSelected] = useState<MapNode | undefined>();
  const [copiesMode, setCopiesMode] = useState(false);

  const map = useQuery({ queryKey: ['map'], queryFn: fetchMap });

  // Scanning a copy jumps straight to the shelf that holds it: the whole point of
  // the map is not walking the aisles to find out you are in the wrong one.
  const { videoRef, cameraReady, cameraError, startCamera, stopCamera } = useScanner(
    (code) => {
      const hit = map.data?.nodes.find((node) =>
        node.items.some((item) => item.code === code || item.barcode === code),
      );
      if (hit) setSelected(hit);
    },
  );

  const placements = useMemo(
    () => (map.data ? project(map.data) : []),
    [map.data],
  );
  const size = useMemo(() => (map.data ? planSize(map.data) : { width: 0, depth: 0 }), [map.data]);
  const chain = useMemo(
    () => (map.data && selected ? pathTo(selected, map.data.nodes) : []),
    [map.data, selected],
  );

  return (
    <div className="lmap">
      <PageHead
        eyebrow="Mapa"
        title="Donde esta cada libro"
        lead="Gira el plano con el dedo y baja por salas, pasillos y estantes. Escanea el codigo y el mapa te lleva al estante."
        actions={
          <div className="lmap__actions">
            <Button
              variant={copiesMode ? 'primary' : 'secondary'}
              onClick={() => setCopiesMode((value) => !value)}
              aria-pressed={copiesMode}
            >
              {copiesMode ? 'Viendo ocupacion' : 'Ver ocupacion'}
            </Button>
            <Button variant="secondary" onClick={() => void startCamera()}>
              Escanear codigo
            </Button>
            {cameraReady && (
              <Button variant="ghost" onClick={stopCamera}>
                Cerrar camara
              </Button>
            )}
          </div>
        }
      />

      {cameraError && (
        <p className="lmap__camera-error" role="alert">
          {cameraError}
        </p>
      )}
      {cameraReady && <video className="lmap__video" ref={videoRef} muted playsInline />}

      <div className="lmap__layout">
        <Card className="lmap__scene-card">
          <CardHeader
            title="Plano de la biblioteca"
            subtitle={
              copiesMode
                ? 'Cada estante se pinta segun cuantos ejemplares estan disponibles'
                : 'Toca un estante para ver que contiene'
            }
            actions={
              selected && (
                <Button variant="ghost" size="sm" onClick={() => setSelected(undefined)}>
                  Ver todo
                </Button>
              )
            }
          />
          <CardBody>
            {map.isPending && <LoadingState label="Cargando el plano" rows={4} />}
            {map.isError && (
              <ErrorState
                message="No se ha podido cargar el plano de la biblioteca."
                onRetry={() => void map.refetch()}
              />
            )}
            {map.data && map.data.nodes.length === 0 && (
              <EmptyState
                title="Todavia no hay ubicaciones"
                description="Crea salas y estantes desde Inventario y apareceran aqui."
                icon="catalog"
              />
            )}

            {map.data && map.data.nodes.length > 0 && (
              <div
                className="lmap__stage"
                // The tilted plane is taller than the plan itself, so the stage
                // grows with it instead of clipping the back row of the library.
                style={{ minHeight: `${size.depth + 140}px` }}
              >
                <div
                  className="lmap__plan"
                  style={{ width: `${size.width}px`, height: `${size.depth}px` }}
                >
                  {placements.map((place) => {
                    const { node } = place;
                    const selectedHere = selected?.id === node.id;
                    const inChain = chain.some((step) => step.id === node.id);
                    const available = node.occupancy.DISPONIBLE ?? 0;
                    return (
                      <button
                        key={node.id}
                        type="button"
                        className="lmap__node"
                        data-kind={node.kind}
                        data-selected={selectedHere || undefined}
                        data-dimmed={selected && !inChain ? 'true' : undefined}
                        data-fill={
                          copiesMode && holdsCopies(node.kind)
                            ? node.copies === 0
                              ? 'empty'
                              : available === 0
                                ? 'full'
                                : 'partial'
                            : undefined
                        }
                        style={{
                          left: `${place.left}px`,
                          top: `${place.top}px`,
                          width: `${place.w}px`,
                          height: `${place.d}px`,
                          // Distant nodes sit dimmer; the lift keeps children above
                          // their parent so a shelf never hides under a room floor.
                          transform: `translateZ(${place.lift}px)`,
                          opacity: 1 - place.depthRatio * 0.35,
                          // A shelf stands on its aisle, so it has to be reachable:
                          // without this the parent box swallows every tap.
                          zIndex: place.level + 1,
                        }}
                        onClick={() =>
                          setSelected((current) => (current?.id === node.id ? undefined : node))
                        }
                        aria-pressed={selectedHere}
                      >
                        <span className="lmap__node-code">{node.code}</span>
                        {copiesMode && holdsCopies(node.kind) && node.copies > 0 && (
                          <span className="lmap__node-count">{available}/{node.copies}</span>
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {map.data && (
              <p className="lmap__legend">
                <span className="lmap__swatch" data-fill="full" /> sin ejemplares
                <span className="lmap__swatch" data-fill="partial" /> alguno disponible
                <span className="lmap__swatch" data-fill="empty" /> vacio
              </p>
            )}
          </CardBody>
        </Card>

        <Card className="lmap__list-card">
          <CardHeader
            title={selected ? selected.name : 'Todas las ubicaciones'}
            subtitle={
              selected
                ? chain.map((step) => step.code).join(' › ')
                : 'La misma informacion del plano, en texto'
            }
          />
          <CardBody>
            {!selected && (
              <ul className="lmap__list">
                {childrenOf(undefined, map.data?.nodes ?? []).map((node) => (
                  <NodeRow key={node.id} node={node} onPick={setSelected} />
                ))}
              </ul>
            )}

            {selected && (
              <>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    const parent = chain[chain.length - 2];
                    setSelected(parent);
                  }}
                  disabled={chain.length < 2}
                >
                  {chain.length > 1 ? `Volver a ${chain[chain.length - 2]!.code}` : 'Volver'}
                </Button>

                <ul className="lmap__list">
                  {childrenOf(selected, map.data?.nodes ?? []).map((node) => (
                    <NodeRow key={node.id} node={node} onPick={setSelected} />
                  ))}
                </ul>

                {holdsCopies(selected.kind) && (
                  <div className="lmap__books">
                    <h3 className="lmap__books-title">
                      Libros en {selected.code}
                      <Badge tone="neutral">{selected.copies}</Badge>
                    </h3>
                    {booksOn(selected).length === 0 && (
                      <p className="lmap__muted">Este estante esta vacio.</p>
                    )}
                    {booksOn(selected).map((book) => (
                      <div key={book.bookId} className="lmap__book">
                        <span className="lmap__book-title">{book.title}</span>
                        <span className="lmap__muted">
                          {book.available} de {book.copies} disponibles
                        </span>
                      </div>
                    ))}
                  </div>
                )}

                {selected.items.length > 0 && (
                  <div className="lmap__items">
                    <h3 className="lmap__books-title">Ejemplares</h3>
                    {selected.items.map((item) => (
                      <div key={item.copyId} className="lmap__item">
                        <span className="lmap__item-code">{item.code}</span>
                        <Badge
                          tone={item.status === 'DISPONIBLE' ? 'success' : 'warning'}
                          dot
                        >
                          {item.status === 'DISPONIBLE' ? 'Disponible' : item.status}
                        </Badge>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </CardBody>
        </Card>
      </div>
    </div>
  );
}

function NodeRow({
  node,
  onPick,
}: {
  node: MapNode;
  onPick: (node: MapNode) => void;
}) {
  const available = node.occupancy.DISPONIBLE ?? 0;
  return (
    <li>
      <button type="button" className="lmap__row" onClick={() => onPick(node)}>
        <span className="lmap__row-code">{node.code}</span>
        <span className="lmap__row-name">{node.name}</span>
        <Badge tone="neutral">{KIND_LABEL[node.kind]}</Badge>
        {node.copies > 0 && (
          <span className="lmap__row-count">
            {available}/{node.copies}
          </span>
        )}
      </button>
    </li>
  );
}
