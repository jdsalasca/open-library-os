import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { lookupIsbn, lookupMessage, sourceLabel, type ExternalBook } from '../../api/isbn';
import { Badge, Button, Card, CardBody, Field } from '../../components';

import './IsbnLookupForm.scss';

type Props = {
  /** Called only when the librarian confirms, so nothing is overwritten by surprise. */
  onApply: (book: ExternalBook) => void;
};

/**
 * "Look up by ISBN": type 13 digits, get a filled-in book, apply it if it is right.
 *
 * <p>The result is always shown before anything is applied. Autofill that silently
 * overwrites the form is worse than no autofill, because the mistake is invisible.
 */
export function IsbnLookupForm({ onApply }: Props) {
  const [isbn, setIsbn] = useState('');
  const [result, setResult] = useState<ExternalBook | null>(null);
  const [error, setError] = useState<string | null>(null);

  const lookup = useMutation({
    mutationFn: (value: string) => lookupIsbn(value),
    onSuccess: (book) => {
      setResult(book);
      setError(null);
    },
    onError: (cause) => {
      setResult(null);
      setError(lookupMessage(cause));
    },
  });

  function search() {
    const value = isbn.trim();
    if (!value) return;
    lookup.mutate(value);
  }

  return (
    <Card>
      <CardBody>
        <form
          className="isbn-lookup"
          onSubmit={(event) => {
            event.preventDefault();
            search();
          }}
        >
          <Field
            label="ISBN"
            hint="Escribe el ISBN y rellenamos la ficha del libro."
            error={error ?? undefined}
            value={isbn}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setIsbn(event.target.value);
              if (error) setError(null);
            }}
          />

          <div className="isbn-lookup__actions">
            <Button
              type="submit"
              variant="primary"
              disabled={lookup.isPending || isbn.trim().length === 0}
            >
              {lookup.isPending ? 'Buscando…' : 'Buscar por ISBN'}
            </Button>
          </div>

          {/*
            One live region, and only for the success case. The failure text is already
            rendered by the Field, which links it with aria-describedby; repeating it here
            would make a screen reader say it twice.
          */}
          <p className="isbn-lookup__status" role="status">
            {result ? `Datos de ${sourceLabel(result.source)}. Revísalos antes de aplicar.` : ''}
          </p>
        </form>

        {result && (
          <div className="isbn-lookup__preview">
            <div className="isbn-lookup__preview-head">
              <h3 className="isbn-lookup__title">
                {result.title ?? 'Sin título'}
                {result.subtitle ? `: ${result.subtitle}` : ''}
              </h3>
              <Badge tone="info">{sourceLabel(result.source)}</Badge>
            </div>

            <dl className="isbn-lookup__facts">
              {result.authors.length > 0 && (
                <div>
                  <dt>Autores</dt>
                  <dd>{result.authors.join(', ')}</dd>
                </div>
              )}
              {result.publisher && (
                <div>
                  <dt>Editorial</dt>
                  <dd>{result.publisher}</dd>
                </div>
              )}
              {result.publicationYear && (
                <div>
                  <dt>Año</dt>
                  <dd>{result.publicationYear}</dd>
                </div>
              )}
              {result.pages !== null && (
                <div>
                  <dt>Páginas</dt>
                  <dd>{result.pages}</dd>
                </div>
              )}
            </dl>

            <div className="isbn-lookup__preview-actions">
              <Button variant="primary" onClick={() => onApply(result)}>
                Aplicar al formulario
              </Button>
              <Button variant="ghost" onClick={() => setResult(null)}>
                Descartar
              </Button>
            </div>
          </div>
        )}
      </CardBody>
    </Card>
  );
}
