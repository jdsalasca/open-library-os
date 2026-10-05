import { describe, expect, it, vi, beforeEach } from 'vitest';
import { lookupIsbn, IsbnLookupError } from '../api/isbn';
import { ApiError } from '../api/client';

function problem(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

describe('lookupIsbn', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls the ISBN endpoint and returns the book', async () => {
    const book = {
      isbn: '9780306406157',
      title: 'Neuromancer',
      subtitle: null,
      authors: ['William Gibson'],
      publisher: 'Ace',
      publicationYear: 1984,
      categories: ['Science fiction'],
      language: 'en',
      pages: 271,
      summary: null,
      coverUrl: null,
      source: 'openlibrary',
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(book), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const found = await lookupIsbn('9780306406157');

    expect(found.title).toBe('Neuromancer');
    expect(found.source).toBe('openlibrary');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/isbn/9780306406157'),
      expect.objectContaining({ credentials: 'include' }),
    );
  });

  it('encodes the ISBN so it is safe inside a URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await lookupIsbn('978 030 640 6157').catch(() => undefined);

    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).not.toContain(' ');
  });

  it('raises a typed error the UI can branch on when the checksum fails', async () => {
    // A Response body can only be read once, so the stub must build a fresh one per call.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(problem(400, { code: 'invalid_isbn', detail: 'ISBN no valido' })),
      ),
    );

    await expect(lookupIsbn('9780306406158')).rejects.toBeInstanceOf(IsbnLookupError);
    await expect(lookupIsbn('9780306406158')).rejects.toMatchObject({
      reason: 'invalid',
    });
  });

  it('raises a typed error when no provider knows the book', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(problem(404, { code: 'not_found', detail: 'Nadie lo conoce' })),
    );

    await expect(lookupIsbn('9780306406157')).rejects.toMatchObject({ reason: 'unknown' });
  });

  it('raises a typed error when the network or the server fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(lookupIsbn('9780306406157')).rejects.toMatchObject({ reason: 'unreachable' });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(500, { detail: 'boom' })));
    await expect(lookupIsbn('9780306406157')).rejects.toMatchObject({ reason: 'unreachable' });
  });

  it('never leaks a raw ApiError to the caller', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(problem(403, { code: 'forbidden', detail: 'Sin permiso' })),
    );

    const error = await lookupIsbn('9780306406157').catch((e) => e);

    expect(error).toBeInstanceOf(IsbnLookupError);
    expect(error).not.toBeInstanceOf(ApiError);
  });
});
