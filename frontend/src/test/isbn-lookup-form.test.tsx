import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { IsbnLookupForm } from '../features/isbn/IsbnLookupForm';

function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

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

describe('IsbnLookupForm', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('labels the field and does not search on its own', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    render(<IsbnLookupForm onApply={() => {}} />, { wrapper: wrapper() });

    expect(screen.getByLabelText(/ISBN/i)).toBeDefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows what the provider returned before anything is applied', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(book))));
    const onApply = vi.fn();
    render(<IsbnLookupForm onApply={onApply} />, { wrapper: wrapper() });

    await userEvent.type(screen.getByLabelText(/ISBN/i), '9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar/i }));

    await waitFor(() => expect(screen.getByText('Neuromancer')).toBeDefined());
    expect(screen.getByText(/William Gibson/)).toBeDefined();
    // The source appears twice on purpose: as a badge on the preview and in the live
    // region. Scope the assertion to the preview so this test cannot drift.
    const badge = screen.getByRole('heading', { name: /Neuromancer/ }).closest('.isbn-lookup__preview');
    expect(badge).not.toBeNull();
    expect(badge?.textContent).toMatch(/Open Library/);
    // Nothing is applied until the librarian confirms.
    expect(onApply).not.toHaveBeenCalled();
  });

  it('applies the book only when the librarian confirms', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(book))));
    const onApply = vi.fn();
    render(<IsbnLookupForm onApply={onApply} />, { wrapper: wrapper() });

    await userEvent.type(screen.getByLabelText(/ISBN/i), '9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar/i }));
    await screen.findByText('Neuromancer');
    await userEvent.click(screen.getByRole('button', { name: /aplicar/i }));

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply.mock.calls[0][0]).toMatchObject({ title: 'Neuromancer' });
  });

  it('offers the manual form when no provider knows the ISBN', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: 'not_found', detail: 'nadie' }), {
          status: 404,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    );
    render(<IsbnLookupForm onApply={() => {}} />, { wrapper: wrapper() });

    await userEvent.type(screen.getByLabelText(/ISBN/i), '9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar/i }));

    await waitFor(() =>
      expect(screen.getByText(/a mano/i)).toBeDefined(),
    );
  });

  it('tells the user to retry when the providers cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    render(<IsbnLookupForm onApply={() => {}} />, { wrapper: wrapper() });

    await userEvent.type(screen.getByLabelText(/ISBN/i), '9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar/i }));

    await waitFor(() => expect(screen.getByText(/más tarde|mas tarde/i)).toBeDefined());
  });

  it('announces the result to assistive technology', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(book))));
    render(<IsbnLookupForm onApply={() => {}} />, { wrapper: wrapper() });

    await userEvent.type(screen.getByLabelText(/ISBN/i), '9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar/i }));

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(/Open Library/),
    );
  });
});
