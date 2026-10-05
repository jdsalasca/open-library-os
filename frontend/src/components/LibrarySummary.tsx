import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';

import { suggestions, type BookSummary } from '../api/catalog';
import { Card, CardBody, CardHeader, EmptyState, Field, Skeleton } from './index';
import './library-summary.scss';

/**
 * What the shelves hold, and a way into them.
 *
 * A shelf count is the number a library quotes on the phone ("do you have...?"
 * "how many do you have?"), so it belongs on the first screen. The newest
 * arrivals are what somebody with a hand on the door wants next.
 */
export function LibrarySummary() {
  const [term, setTerm] = useState('');
  const [debounced, setDebounced] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term.trim()), 250);
    return () => clearTimeout(timer);
  }, [term]);

  const shelf = useQuery({
    queryKey: ['catalog', 'suggestions'],
    queryFn: () => suggestions(),
    staleTime: 60_000,
  });

  const search = useQuery({
    queryKey: ['catalog', 'suggestions', debounced],
    queryFn: () => suggestions(debounced),
    enabled: debounced.length >= 2,
    staleTime: 30_000,
  });

  const recent = shelf.data?.recent ?? [];
  const results = search.data?.results ?? [];
  const searching = debounced.length >= 2;

  return (
    <Card>
      <CardHeader
        title="Tu biblioteca"
        subtitle={
          shelf.data ? `${count(shelf.data.totalBooks)} en el catalogo` : 'Consultando el catalogo'
        }
      />
      <CardBody>
        <Field
          label="Buscar"
          placeholder="Titulo, autor o ISBN"
          value={term}
          onChange={(event) => setTerm(event.target.value)}
          hint="No hace falta la tilde"
        />

        {shelf.isPending && recent.length === 0 && <Skeleton height="3rem" />}

        {searching && (
          <ul className="library__hits">
            {search.isPending && <li className="library__note">Buscando…</li>}
            {search.isError && <li className="library__note">No se pudo buscar.</li>}
            {search.data && results.length === 0 && (
              <li className="library__note">Sin resultados</li>
            )}
            {results.map((book) => (
              <BookHit key={book.id} book={book} />
            ))}
          </ul>
        )}

        {!searching && recent.length > 0 && (
          <>
            <h3 className="library__heading">Lo ultimo que entro</h3>
            <ul className="library__hits">
              {recent.map((book) => (
                <BookHit key={book.id} book={book} />
              ))}
            </ul>
          </>
        )}

        {!searching && shelf.data && recent.length === 0 && (
          <EmptyState
            title="Catalogo vacio"
            description="Aun no hay libros. Empieza por el formulario de alta."
          />
        )}
      </CardBody>
    </Card>
  );
}

function BookHit({ book }: { book: BookSummary }) {
  const authors = book.authors.map((a) => a.name).join(', ');
  return (
    <li>
      <Link className="library__hit" to={`/catalogo/${book.id}`}>
        <span className="library__title">{book.title}</span>
        {authors && <span className="library__authors">{authors}</span>}
        {book.publicationYear && <span className="library__year">{book.publicationYear}</span>}
      </Link>
    </li>
  );
}

/** 1284 is easier to read as 1.284 than as 1284. */
function count(n: number) {
  return new Intl.NumberFormat('es-ES').format(n);
}