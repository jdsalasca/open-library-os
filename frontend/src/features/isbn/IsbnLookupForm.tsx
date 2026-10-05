import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { lookupIsbn, lookupMessage, sourceLabel, type ExternalBook } from '../../api/isbn';
import { Badge, Button, Card, CardBody } from '../../components';

import './IsbnLookupForm.scss';

type Props = {
  /** Called only when the librarian confirms, so nothing is overwritten by surprise. */
  onApply: (book: ExternalBook) => void;
  /** The ISBN already typed in the book form, so nobody types it twice. */
  value: string;
};

/**
 * "Look up by ISBN": fill the book from its code, apply it only if it is right.
 *
 * <p>The result is always shown before anything is applied. Autofill that silently
 * overwrites the form is worse than no autofill, because the mistake is invisible.
 */
export function IsbnLookupForm({ onApply, value }: Props) {
  const isbn = value;
  const [result, setResult] = useState<ExternalBook | null>(null);
  const [error, setError] = useState<string | null>(null);

  const lookup = useMutation({
    mutationFn: (code: string) => lookupIsbn(code),
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
    const trimmed = isbn.trim();
    if (!trimmed) return;
    lookup.mutate(trimmed);
  }

  return (
    <Card>
      <CardBody>
        {/*
          A div, not a form: this lives inside the book form, and nested forms are
          invalid HTML — the browser closes the outer one and the submit breaks.
          There is no ISBN input here either: the book form already has one, and two
          fields showing the same code is just a second thing to keep in sync.
        */}
        <div className="isbn-lookup">
          <div className="isbn-lookup__actions">
            <Button
              type="button"
              variant="primary"
              onClick={search}
              disabled={lookup.isPending || isbn.trim().length === 0}
            >
              {lookup.isPending ? 'Buscando…' : 'Buscar por ISBN'}
            </Button>
          </div>

          {/* The ISBN field belongs to the book form, so the failure is announced here
              instead of next to that field, with role="alert" so it is read at once. */}
          {error && (
            <p className="isbn-lookup__error" role="alert">
              {error}
            </p>
          )}

          {/*
            One live region, and only for the success case: repeating the failure here
            too would make a screen reader say it twice.
          */}
          <p className="isbn-lookup__status" role="status">
            {result ? `Datos de ${sourceLabel(result.source)}. Revísalos antes de aplicar.` : ''}
          </p>
        </div>

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
