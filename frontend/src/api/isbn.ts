import { api, ApiError } from './client';

/** A book as the backend normalises every provider into. */
export type ExternalBook = {
  isbn: string;
  title: string | null;
  subtitle: string | null;
  authors: string[];
  publisher: string | null;
  publicationYear: number | null;
  categories: string[];
  language: string | null;
  pages: number | null;
  summary: string | null;
  coverUrl: string | null;
  source: string;
};

/** Human label for the provider, so the UI can say where the data came from. */
export const SOURCE_LABEL: Record<string, string> = {
  openlibrary: 'Open Library',
  googlebooks: 'Google Books',
};

export function sourceLabel(source: string | undefined): string {
  if (!source) return '';
  return SOURCE_LABEL[source] ?? source;
}

/**
 * Why a lookup failed, in terms the UI can act on.
 *
 * - `invalid` the ISBN itself is wrong: ask for it again.
 * - `unknown` nobody has this book: offer the manual form.
 * - `unreachable` the providers or our own server are down: suggest trying later.
 */
export type LookupFailure = 'invalid' | 'unknown' | 'unreachable';

export class IsbnLookupError extends Error {
  readonly reason: LookupFailure;
  readonly isbn: string;

  constructor(reason: LookupFailure, isbn: string, message: string) {
    super(message);
    this.name = 'IsbnLookupError';
    this.reason = reason;
    this.isbn = isbn;
  }

  /** True when the librarian should just fill the form in by hand. */
  get canFillManually(): boolean {
    return this.reason !== 'unreachable';
  }
}

/** Message worth showing next to the ISBN field. */
export function lookupMessage(error: unknown): string {
  if (!(error instanceof IsbnLookupError)) return 'No se ha podido consultar el ISBN.';
  switch (error.reason) {
    case 'invalid':
      return 'Ese ISBN no es válido. Revísalo, o escribe el libro a mano.';
    case 'unknown':
      return 'Ningún proveedor conoce ese ISBN. Puedes escribir el libro a mano.';
    default:
      return 'No se pudo contactar con los proveedores. Inténtalo más tarde o escríbelo a mano.';
  }
}

/**
 * Fills a book from its ISBN.
 *
 * <p>Never throws a raw {@link ApiError}: the form needs to know whether to ask for a
 * corrected ISBN, offer the manual form, or simply tell the user to try again.
 */
export async function lookupIsbn(isbn: string, signal?: AbortSignal): Promise<ExternalBook> {
  const trimmed = isbn.trim();
  try {
    return await api.get<ExternalBook>(`/isbn/${encodeURIComponent(trimmed)}`, signal);
  } catch (error) {
    throw toLookupError(error, trimmed);
  }
}

function toLookupError(error: unknown, isbn: string): IsbnLookupError {
  if (error instanceof ApiError) {
    if (error.status === 400) {
      return new IsbnLookupError('invalid', isbn, 'ISBN no válido.');
    }
    if (error.status === 404) {
      return new IsbnLookupError('unknown', isbn, 'Ningún proveedor conoce ese ISBN.');
    }
    if (error.status === 401 || error.status === 403) {
      return new IsbnLookupError(
        'unreachable',
        isbn,
        'Necesitas iniciar sesión para usar el relleno automático.',
      );
    }
  }
  // Network down, or our server broken: the ISBN may well be fine.
  return new IsbnLookupError('unreachable', isbn, 'No se pudo contactar con los proveedores.');
}
