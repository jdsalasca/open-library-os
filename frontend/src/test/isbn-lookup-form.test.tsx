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

function problem(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

describe('IsbnLookupForm', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  /** The ISBN field lives in the book form; this component only searches it. */
  function renderForm(isbn: string, onApply = () => {}) {
    return render(<IsbnLookupForm onApply={onApply} value={isbn} />, { wrapper: wrapper() });
  }

  it('does not search until the librarian asks for it', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    renderForm('9780306406157');

    expect(screen.getByRole('button', { name: /buscar por isbn/i })).toBeDefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('searches the ISBN it was given', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(book)));
    vi.stubGlobal('fetch', fetchMock);

    renderForm('9780306406157');
    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));

    await waitFor(() => expect(screen.getByText('Neuromancer')).toBeDefined());
    expect(String(fetchMock.mock.calls[0][0])).toContain('/isbn/9780306406157');
  });

  it('disables the button while there is nothing to search', () => {
    vi.stubGlobal('fetch', vi.fn());
    renderForm('   ');

    expect(screen.getByRole('button', { name: /buscar por isbn/i })).toBeDisabled();
  });

  it('shows what the provider returned before anything is applied', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(book))));
    const onApply = vi.fn();
    renderForm('9780306406157', onApply);

    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));

    await waitFor(() => expect(screen.getByText('Neuromancer')).toBeDefined());
    expect(screen.getByText(/William Gibson/)).toBeDefined();
    // The source appears twice on purpose: as a badge and in the live region.
    const preview = screen.getByRole('heading', { name: /Neuromancer/ }).closest('.isbn-lookup__preview');
    expect(preview?.textContent).toMatch(/Open Library/);
    expect(onApply).not.toHaveBeenCalled();
  });

  it('applies the book only when the librarian confirms', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(book))));
    const onApply = vi.fn();
    renderForm('9780306406157', onApply);

    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));
    await screen.findByText('Neuromancer');
    await userEvent.click(screen.getByRole('button', { name: /aplicar/i }));

    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply.mock.calls[0][0]).toMatchObject({ title: 'Neuromancer' });
  });

  it('offers the manual form when no provider knows the ISBN', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(404, { code: 'not_found' })));
    renderForm('9780306406157');

    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/a mano/i));
  });

  it('tells the user to retry when the providers cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    renderForm('9780306406157');

    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/más tarde|mas tarde/i),
    );
  });

  it('announces a successful lookup to assistive technology', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(book))));
    renderForm('9780306406157');

    await userEvent.click(screen.getByRole('button', { name: /buscar por isbn/i }));

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/Open Library/));
  });
});
