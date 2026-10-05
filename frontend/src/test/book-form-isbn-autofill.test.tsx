import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { BookForm } from '../pages/BookForm';

/**
 * The autofill is only worth anything if it lands in the real book form. These tests
 * cover the mapping, because that is where a provider's shape meets ours.
 */
function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  // BookForm navigates after saving, so it needs a router to exist.
  return ({ children }: { children: React.ReactNode }) => (
    <MemoryRouter>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </MemoryRouter>
  );
}

const book = {
  isbn: '9780306406157',
  title: 'Neuromancer',
  subtitle: null,
  authors: ['William Gibson', 'Bruce Sterling'],
  publisher: 'Ace',
  publicationYear: 1984,
  categories: ['Ciencia ficción'],
  language: 'en',
  pages: 271,
  summary: 'Case, el mejor despachador neural.',
  coverUrl: 'https://example.test/c.jpg',
  source: 'openlibrary',
};

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/isbn/')) {
        return Promise.resolve(
          new Response(JSON.stringify(book), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      if (url.includes('/api/catalog/categories')) {
        return Promise.resolve(
          new Response(JSON.stringify([]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      // Any other catalog call must not be mistaken for the ISBN lookup.
      return Promise.resolve(
        new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      );
    }),
  );
}

describe('BookForm con relleno por ISBN', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    stubApi();
  });

  async function lookUp(isbn: string) {
    render(<BookForm />, { wrapper: wrapper() });
    const field = screen.getByLabelText('ISBN');
    await userEvent.clear(field);
    await userEvent.type(field, isbn);
    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));
  }

  it('copia los datos del proveedor al formulario al confirmar', async () => {
    await lookUp('9780306406157');
    await userEvent.click(await screen.findByRole('button', { name: /aplicar/i }));

    await waitFor(() => {
      expect(screen.getByLabelText('Titulo')).toHaveValue('Neuromancer');
    });
    expect(screen.getByLabelText('Editorial')).toHaveValue('Ace');
    expect(screen.getByLabelText('Año')).toHaveValue(1984);
    expect(screen.getByLabelText('Paginas')).toHaveValue(271);
    expect(screen.getByLabelText('Idioma')).toHaveValue('en');
  });

  it('vuelve a escribir el ISBN canonico que devuelve el backend', async () => {
    await lookUp('0-306-40615-2');
    await userEvent.click(await screen.findByRole('button', { name: /aplicar/i }));

    await waitFor(() => {
      expect(screen.getByLabelText('ISBN')).toHaveValue('9780306406157');
    });
  });

  it('conserva el titulo ya escrito si el proveedor no trae ninguno', async () => {
    render(<BookForm />, { wrapper: wrapper() });

    const title = screen.getByLabelText('Titulo');
    await userEvent.type(title, 'Titulo escrito a mano');

    const field = screen.getByLabelText('ISBN');
    await userEvent.type(field, '9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));

    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/api/isbn/')) {
          // A provider that only knows the ISBN: no title at all.
          return Promise.resolve(
            new Response(JSON.stringify({ ...book, title: null }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
          );
        }
        return Promise.resolve(
          new Response(url.includes('/categories') ? '[]' : '{}', {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }),
    );

    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));
    await userEvent.click(await screen.findByRole('button', { name: /aplicar/i }));

    // The typed title must survive: autofill never deletes manual work.
    expect(screen.getByLabelText('Titulo')).toHaveValue('Titulo escrito a mano');
  });
});
