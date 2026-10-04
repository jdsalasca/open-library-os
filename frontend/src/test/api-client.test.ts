import { describe, expect, it, vi, beforeEach } from 'vitest';

import { api, ApiError } from '../api/client';

function headersOf(index = 0): Record<string, string> {
  const init = vi.mocked(fetch).mock.calls[index][1] as RequestInit | undefined;
  return (init?.headers ?? {}) as Record<string, string>;
}

describe('api client', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    vi.stubGlobal('fetch', vi.fn());
  });

  it('sends cookies and the CSRF header on writes', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    await api.post('/books', { title: 'Dune' });

    const init = vi.mocked(fetch).mock.calls[0][1];
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('include');
    expect(headersOf()['X-XSRF-TOKEN']).toBe('token-123');
    expect(init?.body).toBe(JSON.stringify({ title: 'Dune' }));
  });

  it('never sends the CSRF header on reads', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('[]', { status: 200 }));

    await api.get('/books');

    expect(headersOf()['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('turns a ProblemDetail into a field-level ApiError', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(
        JSON.stringify({
          status: 400,
          title: 'Bad Request',
          detail: 'Datos invalidos',
          code: 'validation',
          fieldErrors: [{ field: 'isbn13', message: 'ISBN no valido' }],
        }),
        { status: 400, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    const error = await api.get('/books').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('Datos invalidos');
    expect((error as ApiError).code).toBe('validation');
    expect((error as ApiError).fieldError('isbn13')).toBe('ISBN no valido');
  });

  it('survives a non-JSON error body', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('gateway timeout', { status: 504 }));

    const error = (await api.get('/books').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(504);
    expect(error.message).toBe('gateway timeout');
  });
});