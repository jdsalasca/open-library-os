import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';

import { deleteBook, getBook } from '../api/catalog';
import { myLibrary } from '../api/myLibrary';
import { RESERVE_TEXT, reserveBook } from '../api/loans';
import { ApiError } from '../api/client';
import { Badge, Button, Card, CardBody, CardHeader, ErrorState, LoadingState, PageHead } from '../components';
import { useAuth } from '../auth/auth-context';
import './BookDetail.scss';

export function BookDetail() {
  const { id } = useParams();
  const bookId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { can } = useAuth();
  const [confirming, setConfirming] = useState(false);

  const book = useQuery({
    queryKey: ['book', bookId],
    queryFn: () => getBook(bookId),
    enabled: Number.isFinite(bookId),
  });

  const remove = useMutation({
    mutationFn: () => deleteBook(bookId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['books'] });
      navigate('/catalogo', { replace: true });
    },
  });

  if (!Number.isFinite(bookId)) {
    return <ErrorState title="Libro no valido" message="La direccion no contiene un identificacion." />;
  }

  if (book.isPending) {
    return (
      <Card>
        <CardBody>
          <LoadingState label="Cargando libro" rows={4} />
        </CardBody>
      </Card>
    );
  }

  if (book.isError) {
    return (
      <div className="book">
        <ErrorState
          title="No encontramos ese libro"
          message="Puede que se haya borrado o que la direccion sea incorrecta."
          onRetry={() => void book.refetch()}
        />
        <Button to="/catalogo" variant="secondary" size="sm">
          Volver al catalogo
        </Button>
      </div>
    );
  }

  const data = book.data;
  const facts: Array<[string, string | number | undefined]> = [
    ['Editorial', data.publisher],
    ['Año', data.publicationYear],
    ['Paginas', data.pages],
    ['Idioma', data.language],
    ['Edicion', data.edition],
    ['ISBN-13', data.isbn13],
    ['ISBN-10', data.isbn10],
  ];

  return (
    <div className="book">
      <PageHead
        eyebrow="Catalogo"
        title={data.title}
        lead={data.authors.map((a) => a.name).join(', ')}
        actions={
          <>
            <ReserveAction bookId={data.id} />
            {can('catalog:write') && (
              <>
                <Button to={`/catalogo/${data.id}/editar`} variant="secondary" size="sm">
                  Editar
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => (confirming ? remove.mutate() : setConfirming(true))}
                  loading={remove.isPending}
                >
                  {confirming ? 'Confirma el borrado' : 'Borrar'}
                </Button>
              </>
            )}
          </>
        }
      />

      <div className="book__grid">
        <div className="book__main">
          {data.summary && (
            <Card>
              <CardHeader title="Resumen" />
              <CardBody>
                <p className="book__summary">{data.summary}</p>
              </CardBody>
            </Card>
          )}

          <Card>
            <CardHeader title="Autores" subtitle={`${data.authors.length} en esta edicion`} />
            <CardBody>
              <ul className="book__authors">
                {data.authors.map((author) => (
                  <li key={author.id} className="book__author">
                    <span className="book__author-name">{author.name}</span>
                    <Badge tone="neutral">{author.role}</Badge>
                  </li>
                ))}
              </ul>
            </CardBody>
          </Card>
        </div>

        <div className="book__side">
          <Card>
            <CardHeader title="Ficha" />
            <CardBody>
              <dl className="book__facts">
                {facts
                  .filter(([, value]) => value !== undefined && value !== '')
                  .map(([label, value]) => (
                    <div key={label}>
                      <dt>{label}</dt>
                      <dd>{value}</dd>
                    </div>
                  ))}
              </dl>
            </CardBody>
          </Card>

          {data.categories.length > 0 && (
            <Card>
              <CardHeader title="Categorias" />
              <CardBody>
                <div className="book__chips">
                  {data.categories.map((category) => (
                    <Badge key={category.id} tone="accent">
                      {category.name}
                    </Badge>
                  ))}
                </div>
              </CardBody>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Reserving lives on the book page because that is where a reader finds out a
 * title exists. The backend decides whether it makes sense: a book that is on the
 * shelf must be borrowed at the desk, and a reader who already has it cannot queue.
 */
function ReserveAction({ bookId }: { bookId: number }) {
  const reserve = useMutation({ mutationFn: () => reserveBook(bookId) });
  const mine = useQuery({ queryKey: ['my-library'], queryFn: myLibrary });
  const alreadyQueued = mine.data?.reservations.some((r) => r.bookId === bookId);
  const failure = (error: unknown) => {
    const api = error as ApiError;
    return RESERVE_TEXT[api.code ?? ''] ?? api.message;
  };

  if (alreadyQueued) {
    return (
      <Badge tone="accent" dot>
        Ya lo tienes reservado
      </Badge>
    );
  }

  return (
    <span className="book__reserve">
      <Button
        variant="secondary"
        size="sm"
        loading={reserve.isPending}
        onClick={() => reserve.mutate()}
      >
        Reservar
      </Button>
      {reserve.isError && (
        <span className="book__reserve-error" role="alert">
          {failure(reserve.error)}
        </span>
      )}
      {reserve.isSuccess && (
        <Badge tone="success" dot>
          Reservado
        </Badge>
      )}
    </span>
  );
}
