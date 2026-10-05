import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { MyLibrary_ as MyLibrary } from '../pages/MyLibrary';
import * as api from '../api/myLibrary';
import * as staffHook from '../auth/auth-context';

const soon = new Date(Date.now() + 5 * 86_400_000).toISOString();
const yesterday = new Date(Date.now() - 86_400_000).toISOString();

const loan = {
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
};

function renderMine() {
  vi.spyOn(staffHook, 'useAuth').mockReturnValue({
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
  } as unknown as ReturnType<typeof staffHook.useAuth>);

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <MyLibrary />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Mi biblioteca', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('lets the reader renew without walking to the desk', async () => {
    vi.spyOn(api, 'myLibrary').mockResolvedValue({ loans: [loan], history: [], reservations: [] });
    const renew = vi
      .spyOn(api, 'renewMyLoan')
      .mockResolvedValue({ ...loan, renewals: 1, dueAt: soon });
    const user = userEvent.setup();
    renderMine();

    await user.click(await screen.findByRole('button', { name: /renovar/i }));

    // react-query also hands the mutation a context object, so only the id is
// asserted: that is the part this test is about.
expect(renew.mock.calls[0][0]).toBe(12);
    expect(await screen.findByText(/renovado/i)).toBeTruthy();
  });

  it('explains why the library said no instead of failing silently', async () => {
    vi.spyOn(api, 'myLibrary').mockResolvedValue({ loans: [loan], history: [], reservations: [] });
    vi.spyOn(api, 'renewMyLoan').mockRejectedValue(
      Object.assign(new Error('No se renueva un prestamo vencido: devuelvelo primero.'), {
        status: 422,
      }),
    );
    const user = userEvent.setup();
    renderMine();

    await user.click(await screen.findByRole('button', { name: /renovar/i }));

    // The refusal has to reach the reader, in the alert, not in a console log.
const alert = await screen.findByRole('alert');
expect(alert.textContent).toMatch(/vencido/i);
  });

  it('offers no renewal on a loan that is already late', async () => {
    vi.spyOn(api, 'myLibrary').mockResolvedValue({
      loans: [{ ...loan, dueAt: yesterday, overdue: true }],
      history: [],
      reservations: [],
    });
    renderMine();

    await screen.findByText('El nombre de la rosa');
    // The date is enough to know this one cannot be renewed.
    expect(screen.queryByRole('button', { name: /renovar/i })).toBeNull();
    expect(screen.getByText(/vencido/i)).toBeTruthy();
  });
});