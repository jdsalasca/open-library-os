import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '../api/client';
import {
  HOLDABLE_KINDS,
  KIND_LABEL,
  STATUS_LABEL,
  addCopies,
  createLocation,
  deleteLocation,
  flattenLocations,
  listCopies,
  listLocations,
  listStock,
  moveCopy,
  setCopyStatus,
  type CopyStatus,
  type LocationKind,
} from '../api/inventory';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  LoadingState,
  PageHead,
  Pagination,
  type Column,
  type Tone,
} from '../components';
import type { Copy, LocationNode } from '../api/inventory';
import { useAuth } from '../auth/auth-context';
import './Inventory.scss';

const SIZE = 20;

const STATUS_TONE: Record<CopyStatus, Tone> = {
  DISPONIBLE: 'success',
  PRESTADO: 'info',
  MANTENIMIENTO: 'warning',
  PERDIDO: 'danger',
};

export function Inventory() {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const canWrite = can('inventory:write');

  const [text, setText] = useState('');
  const [term, setTerm] = useState('');
  const [status, setStatus] = useState<CopyStatus | ''>('');
  const [locationId, setLocationId] = useState('');
  const [page, setPage] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);

  const locations = useQuery({ queryKey: ['locations'], queryFn: listLocations });
  const shelves = flattenLocations(locations.data ?? []).filter((node) =>
    HOLDABLE_KINDS.includes(node.kind),
  );

  const copies = useQuery({
    queryKey: ['copies', { term, status, locationId, page }],
    queryFn: () =>
      listCopies({
        q: term || undefined,
        status: status || undefined,
        locationId: locationId ? Number(locationId) : undefined,
        page,
        size: SIZE,
      }),
    placeholderData: (previous) => previous,
  });

  const stock = useQuery({ queryKey: ['stock'], queryFn: listStock });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['copies'] });
    void queryClient.invalidateQueries({ queryKey: ['stock'] });
    void queryClient.invalidateQueries({ queryKey: ['locations'] });
  };

  const move = useMutation({
    mutationFn: ({ id, to }: { id: number; to: number }) => moveCopy(id, to),
    onSuccess: refresh,
    onError: (e) => setError(e as ApiError),
  });

  const changeStatus = useMutation({
    mutationFn: ({ id, to }: { id: number; to: CopyStatus }) => setCopyStatus(id, to),
    onSuccess: refresh,
    onError: (e) => setError(e as ApiError),
  });

  const columns: Column<Copy>[] = [
    {
      key: 'code',
      header: 'Codigo',
      render: (copy) => <span className="inv__code">{copy.code}</span>,
    },
    {
      key: 'book',
      header: 'Libro',
      render: (copy) => (
        <div className="inv__book">
          <span>{copy.bookTitle ?? `Libro ${copy.bookId}`}</span>
          {copy.barcode && <span className="inv__barcode">{copy.barcode}</span>}
        </div>
      ),
    },
    {
      key: 'location',
      header: 'Ubicacion',
      render: (copy) => <span className="inv__muted">{copy.locationCode ?? 'Sin asignar'}</span>,
      hideOnMobile: true,
    },
    {
      key: 'status',
      header: 'Estado',
      render: (copy) => (
        <Badge tone={STATUS_TONE[copy.status]} dot>
          {STATUS_LABEL[copy.status]}
        </Badge>
      ),
    },
    {
      key: 'actions',
      header: 'Acciones',
      align: 'end',
      hideOnMobile: true,
      render: (copy) =>
        canWrite ? (
          <div className="inv__actions">
            <a className="inv__label" href={`/api/inventory/copies/${copy.id}/label.png`} target="_blank" rel="noreferrer">
              Etiqueta
            </a>
            <label className="visually-hidden" htmlFor={`move-${copy.id}`}>
              Mover {copy.code}
            </label>
            <select
              id={`move-${copy.id}`}
              className="inv__select"
              value=""
              disabled={shelves.length === 0}
              onChange={(event) => {
                const target = event.target.value;
                if (target) move.mutate({ id: copy.id, to: Number(target) });
              }}
            >
              <option value="">Mover a…</option>
              {shelves
                .filter((shelf) => shelf.id !== copy.locationId)
                .map((shelf) => (
                  <option key={shelf.id} value={shelf.id}>
                    {shelf.path}
                  </option>
                ))}
            </select>
            <Button
              variant="secondary"
              size="sm"
              onClick={() =>
                changeStatus.mutate({
                  id: copy.id,
                  to: copy.status === 'DISPONIBLE' ? 'MANTENIMIENTO' : 'DISPONIBLE',
                })
              }
            >
              {copy.status === 'DISPONIBLE' ? 'Retirar' : 'Reponer'}
            </Button>
          </div>
        ) : (
          <span className="inv__muted">—</span>
        ),
    },
  ];

  return (
    <div className="inv">
      <PageHead
        eyebrow="Inventario"
        title="Ejemplares"
        lead="Cada ejemplar tiene su codigo. Sirve para localizarlo y para darlo de alta con una etiqueta."
        actions={
          canWrite && stock.data && stock.data.some((row) => row.copies > 0) ? (
            <AddCopies
              stock={stock.data.filter((row) => row.copies > 0)}
              shelves={shelves}
              onDone={refresh}
            />
          ) : undefined
        }
      />

      <Card className="inv__filters">
        <form
          className="inv__search"
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            setPage(0);
            setTerm(text.trim());
          }}
        >
          <Field
            label="Buscar"
            type="search"
            placeholder="Codigo, ISBN o titulo"
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
          <Field
            as="select"
            label="Estado"
            value={status}
            options={[
              { value: '', label: 'Todos' },
              ...(Object.keys(STATUS_LABEL) as CopyStatus[]).map((key) => ({
                value: key,
                label: STATUS_LABEL[key],
              })),
            ]}
            onChange={(event) => {
              setPage(0);
              setStatus(event.target.value as CopyStatus | '');
            }}
          />
          <Field
            as="select"
            label="Ubicacion"
            value={locationId}
            options={[
              { value: '', label: 'Todas' },
              ...shelves.map((shelf) => ({ value: String(shelf.id), label: shelf.path })),
            ]}
            onChange={(event) => {
              setPage(0);
              setLocationId(event.target.value);
            }}
          />
          <div className="inv__actions-cell">
            <Button type="submit">Buscar</Button>
          </div>
        </form>
      </Card>

      <Card flush>
        {copies.isPending && <LoadingState label="Cargando ejemplares" rows={6} />}
        {copies.isError && (
          <ErrorState
            message="No se ha podido cargar el inventario."
            onRetry={() => void copies.refetch()}
          />
        )}
        {copies.data && copies.data.totalElements === 0 && (
          <EmptyState
            title="No hay ejemplares con esos criteria"
            description="Da de alta las primeras copias para empezar a prestarlas."
            icon="catalog"
          />
        )}
        {copies.data && copies.data.totalElements > 0 && (
          <>
            <DataTable
              caption="Ejemplares del inventario"
              columns={columns}
              rows={copies.data.content}
              rowKey={(copy) => copy.id}
            />
            <Pagination
              page={copies.data.page}
              totalPages={copies.data.totalPages}
              totalElements={copies.data.totalElements}
              onPage={setPage}
            />
          </>
        )}
      </Card>

      {error && (
        <p className="inv__error" role="alert">
          {error.message}
        </p>
      )}

      {canWrite && (
        <Locations
          locations={locations.data}
          shelves={shelves}
          onChanged={refresh}
        />
      )}
    </div>
  );
}

function AddCopies({
  stock,
  shelves,
  onDone,
}: {
  stock: Array<{ bookId: number; title: string; copies: number }>;
  shelves: Array<{ id: number; path: string }>;
  onDone: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [bookId, setBookId] = useState(String(stock[0]?.bookId ?? ''));
  const [quantity, setQuantity] = useState(1);
  const [shelf, setShelf] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [created, setCreated] = useState<string[]>([]);

  const add = useMutation({
    mutationFn: () =>
      addCopies({
        bookId: Number(bookId),
        quantity,
        locationId: shelf ? Number(shelf) : undefined,
      }),
    onSuccess: (result) => {
      setCreated(result.created.map((copy) => copy.code));
      setError(null);
      onDone();
    },
    onError: (e) => setError(e as ApiError),
  });

  if (!open) {
    return (
      <Button onClick={() => setOpen(true)}>Anadir ejemplares</Button>
    );
  }

  return (
    <Card className="inv__add">
      <CardHeader
        title="Anadir ejemplares"
        subtitle="Se generan los codigos y las etiquetas automaticamente"
        actions={
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
            Cerrar
          </Button>
        }
      />
      <CardBody>
        <div className="inv__add-fields">
          <Field
            as="select"
            label="Libro"
            value={bookId}
            options={stock.map((row) => ({
              value: String(row.bookId),
              label: `${row.title} (${row.copies} ejemplares)`,
            }))}
            onChange={(event) => setBookId(event.target.value)}
          />
          <Field
            label="Cuantos"
            type="number"
            min={1}
            max={200}
            value={quantity}
            onChange={(event) => setQuantity(Math.max(1, Number(event.target.value) || 1))}
          />
          <Field
            as="select"
            label="Ubicacion"
            value={shelf}
            options={[
              { value: '', label: 'Sin asignar todavia' },
              ...shelves.map((s) => ({ value: String(s.id), label: s.path })),
            ]}
            onChange={(event) => setShelf(event.target.value)}
          />
        </div>

        {error && (
          <p className="inv__error" role="alert">
            {error.message}
            {error.fieldError('bookId') && `: ${error.fieldError('bookId')}`}
          </p>
        )}

        {created.length > 0 && (
          <div className="inv__created">
            <p>Codigos generados:</p>
            <ul>
              {created.map((code) => (
                <li key={code}>{code}</li>
              ))}
            </ul>
            <p className="inv__hint">
              Abre cada etiqueta desde la tabla y imprimela en el lomo del ejemplar.
            </p>
          </div>
        )}

        <Button loading={add.isPending} onClick={() => add.mutate()}>
          Generar {quantity} {quantity === 1 ? 'ejemplar' : 'ejemplares'}
        </Button>
      </CardBody>
    </Card>
  );
}

function Locations({
  locations,
  shelves,
  onChanged,
}: {
  locations: LocationNode[] | undefined;
  shelves: Array<{ id: number }>;
  onChanged: () => void;
}) {
  const [adding, setAdding] = useState(false);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [kind, setKind] = useState<LocationKind>('ESTANTE');
  const [parentId, setParentId] = useState('');
  const [error, setError] = useState<ApiError | null>(null);

  const create = useMutation({
    mutationFn: () =>
      createLocation({
        code: code.trim(),
        name: name.trim(),
        kind,
        parentId: parentId ? Number(parentId) : undefined,
      }),
    onSuccess: () => {
      setAdding(false);
      setCode('');
      setName('');
      setError(null);
      onChanged();
    },
    onError: (e) => setError(e as ApiError),
  });

  const remove = useMutation({
    mutationFn: deleteLocation,
    onSuccess: onChanged,
    onError: (e) => setError(e as ApiError),
  });

  return (
    <Card>
      <CardHeader
        title="Ubicaciones"
        subtitle="Salas, pasillos y estantes. Solo un estante o deposito guarda ejemplares."
        actions={
          <Button variant="secondary" size="sm" onClick={() => setAdding((v) => !v)}>
            {adding ? 'Cancelar' : 'Anadir ubicacion'}
          </Button>
        }
      />
      <CardBody>
        {adding && (
          <form
            className="inv__loc-form"
            onSubmit={(event) => {
              event.preventDefault();
              create.mutate();
            }}
          >
            <Field
              label="Codigo"
              required
              placeholder="E-3"
              value={code}
              onChange={(event) => setCode(event.target.value)}
            />
            <Field
              label="Nombre"
              required
              placeholder="Estante 3"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <Field
              as="select"
              label="Tipo"
              value={kind}
              options={(Object.keys(KIND_LABEL) as LocationKind[]).map((key) => ({
                value: key,
                label: KIND_LABEL[key],
              }))}
              onChange={(event) => setKind(event.target.value as LocationKind)}
            />
            <Field
              as="select"
              label="Cuelga de"
              value={parentId}
              options={[
                { value: '', label: 'Nada (raiz)' },
                ...(locations ?? [])
                  .filter((node) => node.kind === 'SALA' || node.kind === 'PASILLO')
                  .map((node) => ({ value: String(node.id), label: node.code })),
                ...(locations ?? []).flatMap((node) =>
                  node.children
                    .filter((child) => child.kind === 'SALA' || child.kind === 'PASILLO')
                    .map((child) => ({
                      value: String(child.id),
                      label: `${node.code} / ${child.code}`,
                    })),
                ),
              ]}
              onChange={(event) => setParentId(event.target.value)}
            />
            <Button type="submit" loading={create.isPending}>
              Crear
            </Button>
          </form>
        )}

        {error && (
          <p className="inv__error" role="alert">
            {error.message}
          </p>
        )}

        {locations === undefined && <LoadingState rows={2} />}
        {locations?.length === 0 && (
          <EmptyState
            title="Sin ubicaciones"
            description="Crea un estante para poder guardar ejemplares."
            icon="catalog"
          />
        )}
        {locations && locations.length > 0 && (
          <ul className="inv__tree">
            {flattenLocations(locations).map((node) => (
              <li key={node.id} data-kind={node.kind}>
                <span className="inv__tree-code">{node.code}</span>
                <span className="inv__tree-name">{node.name}</span>
                <Badge tone="neutral">{KIND_LABEL[node.kind]}</Badge>
                <span className="inv__tree-count">{node.copies} ejemplares</span>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={node.copies > 0 || shelves.length === 0}
                  onClick={() => remove.mutate(node.id)}
                  aria-label={`Borrar ${node.code}`}
                >
                  Borrar
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardBody>
    </Card>
  );
}
