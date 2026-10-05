import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { DueSlip } from '../pages/DueSlip';
import * as api from '../api/myLibrary';
import * as authHook from '../auth/auth-context';

const soon = new Date(Date.now() + 3 * 86_400_000).toISOString();
const longAgo = new Date(Date.now() - 6 * 86_400_000).toISOString();

const library = {
  loans: [
    {
      id: 12,
      copyId: 3,
      copyCode: 'OL-0000000003',
      bookId: 4,
      bookTitle: 'El nombre de la rosa',
      readerId: 9,
      borrowedAt: new Date().toISOString(),
      dueAt: soon,
      renewals: 0,
      overdue: false,
    },
    {
      id: 13,
      copyId: 8,
      copyCode: 'OL-0000000008',
      bookId: 6,
      bookTitle: 'Rayuela',
      readerId: 9,
      borrowedAt: new Date().toISOString(),
      dueAt: longAgo,
      renewals: 1,
      overdue: true,
    },
  ],
  history: [],
  reservations: [],
};

function renderSlip(data = library) {
  vi.spyOn(api, 'myLibrary').mockResolvedValue(data);
  vi.spyOn(authHook, 'useAuth').mockReturnValue({
    user: {
      id: 9,
      email: 'ana@demo.test',
      fullName: 'Ana Renovable',
      role: 'LECTOR',
      mustChangePassword: false,
      authorities: ['catalog:read', 'loans:self'],
    },
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  } as unknown as ReturnType<typeof authHook.useAuth>);

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <DueSlip />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('resguardo de prestamos', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('names who the slip belongs to', async () => {
    renderSlip();

    expect(await screen.findByText(/Prestamos de Ana Renovable/i)).toBeTruthy();
    expect(screen.getByText('ana@demo.test')).toBeTruthy();
  });

  it('lists every book with the code the desk will scan back in', async () => {
    renderSlip();

    expect(await screen.findByText('El nombre de la rosa')).toBeTruthy();
    expect(screen.getByText('OL-0000000003')).toBeTruthy();
    expect(screen.getByText('Rayuela')).toBeTruthy();
    expect(screen.getByText('OL-0000000008')).toBeTruthy();
  });

  it('says out loud which ones are late', async () => {
    renderSlip();

    await screen.findByText('El nombre de la rosa');

    const late = screen.getByText(/vencid/i);
    expect(late.textContent).toMatch(/6/);
    // The book that is fine must not look late, or the slip is useless.
    expect(screen.getAllByText(/vencid/i)).toHaveLength(1);
  });

  it('says plainly when there is nothing to return', async () => {
    vi.spyOn(api, 'myLibrary').mockResolvedValue({
      loans: [],
history: [],
      reservations: [],
    });

    renderSlip({ loans: [], history: [], reservations: [] });

    expect(await screen.findByText(/no tienes nada pendiente/i)).toBeTruthy();
  });
});