import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { LayoutEditor } from '../components/LayoutEditor';
import * as api from '../api/inventory';
import type { LocationNode } from '../api/inventory';

const shelf = {
  id: 4,
  code: 'E-1',
  name: 'Estanteria de novelas',
  kind: 'ESTANTE' as const,
  children: [],
  copies: 12,
  x: 2.5,
  z: -1.25,
  width: 0.6,
  depth: 0.35,
};

function renderEditor(nodes: LocationNode[] = [shelf]) {
  vi.spyOn(api, 'listLocations').mockResolvedValue(nodes);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <LayoutEditor />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('editor de montaje', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('says plainly when nothing is placed yet', async () => {
    renderEditor([
      { id: 1, code: 'S', name: 'Sala', kind: 'SALA', children: [], copies: 0 },
    ]);

    expect(await screen.findByText(/todavia no hay nada colocado/i)).toBeTruthy();
  });

it('shows where a shelf stands', async () => {
    const user = userEvent.setup();
    renderEditor();

    await user.click(await screen.findByRole('button', { name: /Estanteria de novelas/i }));
    expect(screen.getByLabelText(/^x$/i)).toHaveValue(2.5);
    expect(screen.getByLabelText(/^z$/i)).toHaveValue(-1.25);
  });

  it('moves a shelf and keeps the numbers it was given', async () => {
    const save = vi.spyOn(api, 'updateLocation').mockResolvedValue({ ...shelf, x: 4 });
    const user = userEvent.setup();
    renderEditor();

    await user.click(await screen.findByRole('button', { name: /Estanteria de novelas/i }));
    await user.clear(screen.getByLabelText(/^x$/i));
    await user.type(screen.getByLabelText(/^x$/i), '4');
    await user.click(screen.getByRole('button', { name: /guardar posicion/i }));

    expect(save.mock.calls[0][0]).toEqual(
      expect.objectContaining({ id: 4, x: 4, z: -1.25 }),
    );
    expect(await screen.findByText(/posicion guardada/i)).toBeTruthy();
  });

  it('refuses a coordinate that would be outside the building', async () => {
    const save = vi.spyOn(api, 'updateLocation');
    const user = userEvent.setup();
    renderEditor();

    await user.click(await screen.findByRole('button', { name: /Estanteria de novelas/i }));
    await user.clear(screen.getByLabelText(/^x$/i));
    await user.type(screen.getByLabelText(/^x$/i), '5000');
    await user.click(screen.getByRole('button', { name: /guardar posicion/i }));

    expect(save).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeTruthy();
  });
});