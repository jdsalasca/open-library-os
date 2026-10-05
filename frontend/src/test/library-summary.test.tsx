import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { LibrarySummary } from '../components/LibrarySummary';
import * as api from '../api/catalog';

function renderSummary() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <LibrarySummary />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('LibrarySummary', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('says how many books the shelves hold', async () => {
    vi.spyOn(api, 'suggestions').mockResolvedValue({
      totalBooks: 1284,
      recent: [],
      results: [],
    });

    renderSummary();

    // Spanish convention (RAE): four digits go without a separator.
    expect(await screen.findByText(/1284 en el catalogo/)).toBeTruthy();
  });

  it('separates thousands once the number needs it', async () => {
    vi.spyOn(api, 'suggestions').mockResolvedValue({
      totalBooks: 12345,
      recent: [],
      results: [],
    });

    renderSummary();

    expect(await screen.findByText(/12.345 en el catalogo/)).toBeTruthy();
  });

  it('shows the newest arrivals as links, not as a wall of text', async () => {
    vi.spyOn(api, 'suggestions').mockResolvedValue({
      totalBooks: 2,
      recent: [
        {
          id: 7,
          title: 'Rayuela',
          subtitle: undefined,
          authors: [{ id: 1, name: 'Julio Cortazar', role: 'AUTOR', position: 0 }],
          publisher: undefined,
          publicationYear: 1963,
          language: 'es',
          pages: undefined,
          coverUrl: undefined,
          isbn13: '9788437604572',
        },
      ],
      results: [],
    });

    renderSummary();

    expect(await screen.findByText('Rayuela', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByText('Julio Cortazar')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Rayuela/ })).toBeTruthy();
  });

  it('finds a book while you type', async () => {
    const search = vi.spyOn(api, 'suggestions').mockResolvedValue({
      totalBooks: 0,
      recent: [],
      results: [
        {
          id: 9,
          title: 'Ficciones',
          subtitle: undefined,
          authors: [{ id: 2, name: 'Borges', role: 'AUTOR', position: 0 }],
          publisher: undefined,
          publicationYear: 1944,
          language: 'es',
          pages: undefined,
          coverUrl: undefined,
          isbn13: '9780802130303',
        },
      ],
    });
    const user = userEvent.setup();
    renderSummary();

    await user.type(screen.getByLabelText(/buscar/i), 'fic');

    expect(await screen.findByText('Ficciones', {}, { timeout: 5000 })).toBeTruthy();
    expect(search).toHaveBeenCalledWith('fic');
  });

  it('says so when a search finds nothing', async () => {
    vi.spyOn(api, 'suggestions').mockResolvedValue({ totalBooks: 0, recent: [], results: [] });
    const user = userEvent.setup();
    renderSummary();

    await user.type(screen.getByLabelText(/buscar/i), 'zzz');

    expect(await screen.findByText(/sin resultados/i, {}, { timeout: 5000 })).toBeTruthy();
  });
});