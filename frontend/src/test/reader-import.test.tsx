import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { ReaderImport } from '../pages/Users';
import * as api from '../api/users';

function csvFile(name: string, body: string) {
  return { name, text: async () => body } as unknown as File;
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ReaderImport onDone={() => {}} />
    </QueryClientProvider>,
  );
}

function chooseFile(body = 'nombre,correo\nAna,ana@example.org\n') {
  fireEvent.change(screen.getByLabelText(/Elegir el CSV/i, { selector: 'input' }), {
    target: { files: [csvFile('socios.csv', body)] },
  });
}

describe('cargar la lista de socios', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('sends the sheet as text and counts what it created', async () => {
    const send = vi
      .spyOn(api, 'importReaders')
      .mockResolvedValue({ created: 2, alreadyThere: 1, failed: 0, problems: [] });

    renderCard();
    chooseFile();

    await waitFor(() =>
      expect(send).toHaveBeenCalledWith('nombre,correo\nAna,ana@example.org\n'),
    );
    expect(await screen.findByText(/2 cuentas nuevas, 1 ya existian/i)).toBeTruthy();
  });

  it('names the line to fix when a row was rejected', async () => {
    vi.spyOn(api, 'importReaders').mockResolvedValue({
      created: 1,
      alreadyThere: 0,
      failed: 1,
      problems: [{ line: 12, reason: 'correo invalido: ana@@' }],
    });

    renderCard();
    chooseFile();

    expect(await screen.findByText(/Linea 12: correo invalido/)).toBeTruthy();
    // Singular when it is one: the page already pluralises the rest of the app.
  });

  it('says so when the sheet has no name column', async () => {
    vi.spyOn(api, 'importReaders').mockRejectedValue(
      new Error('El fichero necesita columnas de nombre y correo.'),
    );

    renderCard();
    chooseFile();

    expect(await screen.findByRole('alert')).toHaveProperty(
      'textContent',
      expect.stringContaining('columnas de nombre y correo'),
    );
  });
});