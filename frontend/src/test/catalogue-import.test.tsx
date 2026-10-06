import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { CatalogueImport } from '../pages/Catalog';
import * as api from '../api/catalog';

function csvFile(name: string, body: string) {
  // A real File in jsdom may not implement text(); what the card needs is a name
  // and a promise of its content, so that is what this is.
  return { name, text: async () => body } as unknown as File;
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CatalogueImport onImported={() => {}} />
    </QueryClientProvider>,
  );
}

describe('cargar el catalogo de una hoja de calculo', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('sends the file as text and tells the desk what it did', async () => {
    const send = vi
      .spyOn(api, 'importCatalogue')
      .mockResolvedValue({ created: 2, updated: 1, copiesAdded: 3, failed: 0, problems: [] });

    const { container } = renderCard();
    const input = screen.getByLabelText(/Elegir el CSV/i, { selector: 'input' });
    fireEvent.change(input, { target: { files: [csvFile('libros.csv', 'titulo,autor\nA,B\n')] } });

    await waitFor(() => expect(send).toHaveBeenCalledWith('titulo,autor\nA,B\n'));
    expect(await screen.findByText(/3 libros guardados/i)).toBeTruthy();

    // The counts live in four list items with the number in its own element, so the
    // text is split across nodes and only the list itself reads as a sentence.
    const counts = container.querySelector('.catalog__import-counts')?.textContent ?? '';
    expect(counts).toContain('2 nuevos');
    expect(counts).toContain('1 actualizados');
    expect(counts).toContain('3 ejemplares creados');
  });

  it('names the line to fix when a row was rejected', async () => {
    vi.spyOn(api, 'importCatalogue').mockResolvedValue({
      created: 1,
      updated: 0,
      copiesAdded: 0,
      failed: 1,
      problems: [{ line: 4, reason: 'ISBN invalido: 123' }],
    });

    renderCard();
    fireEvent.change(screen.getByLabelText(/Elegir el CSV/i, { selector: 'input' }), {
      target: { files: [csvFile('libros.csv', 'titulo\nA\n')] },
    });

    expect(await screen.findByText(/Linea 4: ISBN invalido/)).toBeTruthy();
    expect(screen.getByText(/1 libros guardados, 1 sin guardar/i)).toBeTruthy();
  });

  it('says so when the file could not be read at all', async () => {
    vi.spyOn(api, 'importCatalogue').mockRejectedValue(new Error('El fichero necesita una columna de titulo.'));

    renderCard();
    fireEvent.change(screen.getByLabelText(/Elegir el CSV/i, { selector: 'input' }), {
      target: { files: [csvFile('libros.csv', 'nada\n')] },
    });

    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      expect.stringContaining('columna de titulo'),
    );
  });
});