import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';

import {
  authorNames,
  listCategories,
  listPublishers,
  searchBooks,
  type BookQuery,
  type BookSummary,
} from '../api/catalog';
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  LoadingState,
  PageHead,
  Pagination,
  type Column,
} from '../components';
import { useAuth } from '../auth/auth-context';
import './Catalog.scss';

const SIZE = 20;

const SORTS = [
  { value: 'title:asc', label: 'Titulo (A-Z)' },
  { value: 'title:desc', label: 'Titulo (Z-A)' },
  { value: 'publicationYear:desc', label: 'Mas recientes' },
  { value: 'publicationYear:asc', label: 'Mas antiguos' },
  { value: 'createdAt:desc', label: 'Anadidos ultimo' },
];

export function Catalog() {
  const navigate = useNavigate();
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();

  const [text, setText] = useState(params.get('q') ?? '');

  const query: BookQuery = {
    q: params.get('q') ?? undefined,
    categoryId: params.get('category') ? Number(params.get('category')) : undefined,
    publisherId: params.get('publisher') ? Number(params.get('publisher')) : undefined,
    year: params.get('year') ? Number(params.get('year')) : undefined,
    language: params.get('language') ?? undefined,
    sort: params.get('sort') ?? 'title:asc',
    page: Number(params.get('page') ?? 0),
    size: SIZE,
  };

  const books = useQuery({
    queryKey: ['books', query],
    queryFn: ({ signal }) => searchBooks(query, signal),
    placeholderData: (previous) => previous,
  });

  const categories = useQuery({ queryKey: ['categories'], queryFn: listCategories });
  const publishers = useQuery({ queryKey: ['publishers'], queryFn: listPublishers });

  function update(next: Record<string, string | null>) {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === null || value === '') merged.delete(key);
      else merged.set(key, value);
    }
    // Any filter change invalidates the current page number.
    if (!('page' in next)) merged.delete('page');
    setParams(merged, { replace: true });
  }

  const filtersActive =
    Boolean(query.q) || Boolean(query.categoryId) || Boolean(query.publisherId) ||
    Boolean(query.year) || Boolean(query.language);

  const columns: Column<BookSummary>[] = [
    {
      key: 'title',
      header: 'Titulo',
      render: (book) => (
        <div className="catalog__title">
          <span className="catalog__name">{book.title}</span>
          {book.subtitle && <span className="catalog__subtitle">{book.subtitle}</span>}
        </div>
      ),
    },
    {
      key: 'authors',
      header: 'Autores',
      render: (book) => <span className="catalog__muted">{authorNames(book)}</span>,
      hideOnMobile: false,
    },
    {
      key: 'publisher',
      header: 'Editorial',
      render: (book) => <span className="catalog__muted">{book.publisher ?? '—'}</span>,
      hideOnMobile: true,
    },
    {
      key: 'year',
      header: 'Año',
      align: 'end',
      render: (book) => book.publicationYear ?? '—',
      hideOnMobile: true,
      width: '5rem',
    },
    {
      key: 'isbn',
      header: 'ISBN',
      render: (book) => <span className="catalog__isbn">{book.isbn13 ?? '—'}</span>,
      hideOnMobile: true,
    },
  ];

  return (
    <div className="catalog">
      <PageHead
        eyebrow="Catalogo"
        title="Libros"
        lead="Busca por titulo, autor o ISBN. Los acentos no importan."
        actions={
          can('catalog:write') ? (
            <Button to="/catalogo/nuevo">Anadir libro</Button>
          ) : undefined
        }
      />

      <Card className="catalog__filters">
        <form
          className="catalog__search"
          onSubmit={(event) => {
            event.preventDefault();
            update({ q: text.trim() || null });
          }}
          role="search"
        >
          <Field
            label="Buscar"
            type="search"
            placeholder="Titulo, autor o ISBN"
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
          <Field
            as="select"
            label="Categoria"
            value={params.get('category') ?? ''}
            options={[
              { value: '', label: 'Todas' },
              ...(categories.data ?? []).map((c) => ({ value: String(c.id), label: c.name })),
            ]}
            onChange={(event) => update({ category: event.target.value || null })}
          />
          <Field
            as="select"
            label="Editorial"
            value={params.get('publisher') ?? ''}
            options={[
              { value: '', label: 'Todas' },
              ...(publishers.data ?? []).map((p) => ({ value: String(p.id), label: p.name })),
            ]}
            onChange={(event) => update({ publisher: event.target.value || null })}
          />
          <Field
            as="select"
            label="Ordenar por"
            value={query.sort}
            options={SORTS}
            onChange={(event) => update({ sort: event.target.value })}
          />
          <div className="catalog__actions">
            <Button type="submit">Buscar</Button>
            {filtersActive && (
              <Button
                variant="ghost"
                onClick={() => {
                  setText('');
                  setParams(new URLSearchParams(), { replace: true });
                }}
              >
                Limpiar
              </Button>
            )}
          </div>
        </form>
      </Card>

      <Card flush>
        {books.isPending && <LoadingState label="Buscando libros" rows={6} />}
        {books.isError && (
          <ErrorState
            message="No se ha podido cargar el catalogo."
            onRetry={() => void books.refetch()}
          />
        )}
        {books.data && books.data.totalElements === 0 && (
          <EmptyState
            title="No hay libros con esos criteria"
            description={
              filtersActive
                ? 'Prueba a quitar algun filtro o a buscar otra palabra.'
                : 'Anade el primer libro para empezar.'
            }
            action={can('catalog:write') ? { label: 'Anadir libro', to: '/catalogo/nuevo' } : undefined}
          />
        )}
        {books.data && books.data.totalElements > 0 && (
          <>
            <DataTable
              caption="Libros del catalogo"
              columns={columns}
              rows={books.data.content}
              rowKey={(book) => book.id}
              onRowClick={(book) => navigate(`/catalogo/${book.id}`)}
            />
            <Pagination
              page={books.data.page}
              totalPages={books.data.totalPages}
              totalElements={books.data.totalElements}
              onPage={(page) => update({ page: String(page) })}
            />
          </>
        )}
      </Card>

      {filtersActive && (
        <p className="catalog__hint">
          <Badge tone="accent">
            {books.data?.totalElements ?? 0} resultados
          </Badge>
        </p>
      )}
    </div>
  );
}
