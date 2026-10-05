import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

import { createBook, updateBook, type AuthorInput, type UpsertBook } from '../api/catalog';
import { listCategories } from '../api/catalog';
import { ApiError } from '../api/client';
import type { ExternalBook } from '../api/isbn';
import { IsbnLookupForm } from '../features/isbn/IsbnLookupForm';
import {
  Button,
  Card,
  CardBody,
  CardHeader,
  ErrorState,
  Field,
  PageHead,
} from '../components';
import './BookForm.scss';

const EMPTY: UpsertBook = {
  title: '',
  authors: [{ name: '', role: 'AUTOR' }],
  categories: [],
};

const ROLES = [
  { value: 'AUTOR', label: 'Autor' },
  { value: 'COAUTOR', label: 'Coautor' },
  { value: 'TRADUCTOR', label: 'Traductor' },
  { value: 'ILUSTRADOR', label: 'Ilustrador' },
  { value: 'EDITOR', label: 'Editor' },
];

/** Keeps the categories already chosen and adds the ones the provider brought. */
function mergeCategories(current: string[], incoming: string[]): string[] {
  const known = new Set(current.map((c) => c.toLowerCase()));
  return [...current, ...incoming.filter((c) => !known.has(c.toLowerCase()))];
}

export function BookForm({ bookId }: { bookId?: number }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const editing = bookId !== undefined;

  const [form, setForm] = useState<UpsertBook>(EMPTY);
  const [error, setError] = useState<ApiError | null>(null);

  const categories = useQuery({ queryKey: ['categories'], queryFn: listCategories });

  const existing = useQuery({
    queryKey: ['book', bookId],
    queryFn: async () => {
      const { getBook } = await import('../api/catalog');
      return getBook(bookId!);
    },
    enabled: editing,
    // Seed the form once the record arrives.
    staleTime: Infinity,
  });

  const [seeded, setSeeded] = useState(false);
  if (editing && existing.data && !seeded) {
    setSeeded(true);
    setForm({
      title: existing.data.title,
      subtitle: existing.data.subtitle,
      isbn: existing.data.isbn13,
      publisher: existing.data.publisher,
      publicationYear: existing.data.publicationYear,
      language: existing.data.language,
      pages: existing.data.pages,
      summary: existing.data.summary,
      edition: existing.data.edition,
      authors: existing.data.authors.map((a) => ({ name: a.name, role: a.role })),
      categories: existing.data.categories.map((c) => c.name),
    });
  }

  const save = useMutation({
    mutationFn: (body: UpsertBook) =>
      editing ? updateBook(bookId, body) : createBook(body),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: ['books'] });
      navigate(`/catalogo/${saved.id}`);
    },
    onError: (e) => setError(e as ApiError),
  });

  function setAuthor(index: number, patch: Partial<AuthorInput>) {
    setForm((current) => ({
      ...current,
      authors: current.authors.map((author, i) => (i === index ? { ...author, ...patch } : author)),
    }));
  }

  function toggleCategory(name: string) {
    setForm((current) => ({
      ...current,
      categories: current.categories.includes(name)
        ? current.categories.filter((c) => c !== name)
        : [...current.categories, name],
    }));
  }

  /**
   * Copies what the providers returned into the form, leaving alone any field they did
   * not know: autofill must never delete something somebody typed by hand.
   */
  function applyFromIsbn(book: ExternalBook) {
    setForm((current) => ({
      ...current,
      title: book.title ?? current.title,
      subtitle: book.subtitle ?? current.subtitle,
      isbn: book.isbn,
      publisher: book.publisher ?? current.publisher,
      publicationYear: book.publicationYear ?? current.publicationYear,
      pages: book.pages ?? current.pages,
      language: book.language ?? current.language,
      summary: book.summary ?? current.summary,
      coverUrl: book.coverUrl ?? current.coverUrl,
      authors: book.authors.length
        ? book.authors.map((name) => ({ name, role: 'AUTOR' }))
        : current.authors,
      categories: book.categories.length
        ? mergeCategories(current.categories, book.categories)
        : current.categories,
    }));
  }

  if (editing && existing.isPending) {
    return (
      <Card>
        <CardBody>
          <p>Cargando libro…</p>
        </CardBody>
      </Card>
    );
  }

  if (editing && existing.isError) {
    return (
      <ErrorState
        title="No encontramos ese libro"
        message="Puede que se haya borrado."
        onRetry={() => void existing.refetch()}
      />
    );
  }

  return (
    <div className="bookform">
      <PageHead
        eyebrow="Catalogo"
        title={editing ? 'Editar libro' : 'Nuevo libro'}
        lead="Los autores, la editorial y las categorias se escriben a mano: el catalogo los enlaza si ya existen."
        actions={
          <Button variant="ghost" onClick={() => navigate('/catalogo')}>
            Volver al catalogo
          </Button>
        }
      />

      <form
        className="bookform__grid"
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate({
            ...form,
            authors: form.authors.filter((a) => a.name.trim() !== ''),
          });
        }}
        noValidate
      >
        <Card>
          <CardHeader title="Ficha" subtitle="Lo esencial para encontrarlo" />
          <CardBody>
            <div className="bookform__fields">
              <Field
                label="Titulo"
                required
                value={form.title}
                error={error?.fieldError('title')}
                onChange={(event) => setForm({ ...form, title: event.target.value })}
              />
              <Field
                label="Subtitulo"
                value={form.subtitle ?? ''}
                onChange={(event) => setForm({ ...form, subtitle: event.target.value })}
              />
              <IsbnLookupForm
                value={form.isbn ?? ''}
                onApply={applyFromIsbn}
              />
              <Field
                label="ISBN"
                hint="10 o 13 digitos. Se comprueba el digito de control."
                value={form.isbn ?? ''}
                error={error?.fieldError('isbn')}
                onChange={(event) => setForm({ ...form, isbn: event.target.value })}
              />
              <div className="bookform__row">
                <Field
                  label="Editorial"
                  value={form.publisher ?? ''}
                  onChange={(event) => setForm({ ...form, publisher: event.target.value })}
                />
                <Field
                  label="Año"
                  type="number"
                  value={form.publicationYear ?? ''}
                  error={error?.fieldError('publicationYear')}
                  onChange={(event) =>
                    setForm({
                      ...form,
                      publicationYear: event.target.value ? Number(event.target.value) : undefined,
                    })
                  }
                />
                <Field
                  label="Paginas"
                  type="number"
                  value={form.pages ?? ''}
                  onChange={(event) =>
                    setForm({ ...form, pages: event.target.value ? Number(event.target.value) : undefined })
                  }
                />
              </div>
              <Field
                label="Idioma"
                value={form.language ?? ''}
                onChange={(event) => setForm({ ...form, language: event.target.value })}
              />
              <Field
                as="textarea"
                label="Resumen"
                value={form.summary ?? ''}
                onChange={(event) => setForm({ ...form, summary: event.target.value })}
              />
            </div>
          </CardBody>
        </Card>

        <div className="bookform__side">
          <Card>
            <CardHeader
              title="Autores"
              subtitle="Un libro puede tener varios"
              actions={
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setForm({ ...form, authors: [...form.authors, { name: '', role: 'AUTOR' }] })}
                >
                  Anadir
                </Button>
              }
            />
            <CardBody>
              <ul className="bookform__authors">
                {form.authors.map((author, index) => (
                  <li key={index} className="bookform__author">
                    <Field
                      {...(index === 0
                        ? { label: 'Nombre' }
                        : { ariaLabel: `Nombre del autor ${index + 1}` })}
                      required={index === 0}
                      value={author.name}
                      onChange={(event) => setAuthor(index, { name: event.target.value })}
                    />
                    <Field
                      as="select"
                      {...(index === 0
                        ? { label: 'Rol' }
                        : { ariaLabel: `Rol del autor ${index + 1}` })}
                      options={ROLES}
                      value={author.role}
                      onChange={(event) => setAuthor(index, { role: event.target.value })}
                    />
                    {form.authors.length > 1 && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          setForm({
                            ...form,
                            authors: form.authors.filter((_, i) => i !== index),
                          })
                        }
                        aria-label={`Quitar autor ${author.name || index + 1}`}
                      >
                        Quitar
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
              {error?.fieldError('authors') && (
                <p className="bookform__error" role="alert">
                  {error.fieldError('authors')}
                </p>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Categorias" subtitle="Marca las que apliquen" />
            <CardBody>
              <div className="bookform__chips">
                {(categories.data ?? []).map((category) => {
                  const selected = form.categories.includes(category.name);
                  return (
                    <button
                      key={category.id}
                      type="button"
                      className="bookform__chip"
                      data-selected={selected || undefined}
                      aria-pressed={selected}
                      onClick={() => toggleCategory(category.name)}
                    >
                      {category.name}
                    </button>
                  );
                })}
              </div>
              <Field
                label="Otra categoria"
                hint="Se anade al catalogo la primera vez."
                value=""
                onChange={(event) => {
                  const value = event.target.value.trim();
                  if (value) toggleCategory(value);
                }}
              />
            </CardBody>
          </Card>

          {error && !error.fieldError('title') && (
            <p className="bookform__error" role="alert">
              {error.message}
            </p>
          )}

          <Button type="submit" size="lg" fullWidth loading={save.isPending}>
            {editing ? 'Guardar cambios' : 'Crear libro'}
          </Button>
        </div>
      </form>
    </div>
  );
}
