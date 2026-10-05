import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { BackupCard } from '../components/BackupCard';
import * as api from '../api/admin';

function renderCard(status?: Partial<api.BackupStatus>) {
  vi.spyOn(api, 'backupStatus').mockResolvedValue({
    healthy: true,
    message: 'Respaldos al dia: 3 guardados, el ultimo hace 5 h.',
    lastRun: 1767225600,
    hoursSinceLast: 5,
    dumps: 3,
    newest: { name: 'openlibrary-20260102T000000Z.dump', bytes: 4_194_304 },
    ...status,
  } as api.BackupStatus);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <BackupCard />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('estado de los respaldos', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('says the backups are fine and when the last one was', async () => {
    renderCard();

    expect(await screen.findByText(/Respaldos al dia/)).toBeTruthy();
    expect(screen.getByText(/hace 5 h/)).toBeTruthy();
  });

  it('shows how much space the newest dump takes', async () => {
    renderCard();

    expect(await screen.findByText('4,0 MB')).toBeTruthy();
  });

  it('shouts when there has never been a backup', async () => {
    renderCard({
      healthy: false,
      message: 'No se ha hecho ningun respaldo todavia.',
      dumps: 0,
      hoursSinceLast: 0,
    });

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/todavia/i);
  });

  it('calls out a stale backup with its age in hours', async () => {
    renderCard({
      healthy: false,
      message: 'El ultimo respaldo es de hace 96 horas.',
      hoursSinceLast: 96,
      dumps: 2,
    });

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/96 horas/);
  });
});