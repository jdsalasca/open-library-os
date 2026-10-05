import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { Home } from '../pages/Home';
import * as loansApi from '../api/loans';
import * as systemApi from '../api/system';

const staff = {
  id: 1,
  email: 'bibliotecario@local',
  fullName: 'Ana Biblioteca',
  role: 'BIBLIOTECARIO',
  mustChangePassword: false,
  authorities: ['loans:operate'],
};

/** What the server really hands each role, so the component sees the truth. */
const AUTHORITIES: Record<string, string[]> = {
  LECTOR: ['catalog:read', 'loans:self'],
  BIBLIOTECARIO: ['catalog:read', 'inventory:write', 'loans:operate'],
};

function renderHome(role: string) {
  vi.spyOn(staffHook, 'useAuth').mockReturnValue({
    user: { ...staff, role, authorities: AUTHORITIES[role] ?? [] },
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  } as unknown as ReturnType<typeof staffHook.useAuth>);

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <Home />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

import * as staffHook from '../auth/auth-context';

describe('Home', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(systemApi, 'getHealth').mockResolvedValue({
      status: 'UP',
      components: { db: { status: 'UP' } },
    } as unknown as Awaited<ReturnType<typeof systemApi.getHealth>>);
  });

  it('tells staff who is late, before anything else', async () => {
    const dashboard = vi.spyOn(loansApi, 'loanDashboard').mockResolvedValue({
      out: 12,
      overdue: 3,
      dueToday: 2,
      available: 41,
      urgent: [
        {
          readerName: 'Elena Ibáñez',
          readerEmail: 'elena@demo.test',
          bookTitle: 'Cien anos de soledad',
          daysLate: 9,
        },
      ],
    });

    renderHome('BIBLIOTECARIO');

    expect(await screen.findByText('Elena Ibáñez')).toBeTruthy();
    expect(screen.getByText('Cien anos de soledad')).toBeTruthy();
    // The number a librarian acts on has to be impossible to miss.
    expect(screen.getByText('3')).toBeTruthy();
    expect(screen.getByText(/vencid/i)).toBeTruthy();
    expect(dashboard).toHaveBeenCalled();
  });

  it('says nothing about other peoples debts to a reader', async () => {
    const dashboard = vi.spyOn(loansApi, 'loanDashboard').mockRejectedValue(new Error('403'));

    renderHome('LECTOR');

    expect(await screen.findByText('Tu rincon')).toBeTruthy();
    // A reader gets a pointer to their own corner, never someone else's debts.
    expect(screen.queryByText('Elena Ibáñez')).toBeNull();
    expect(screen.queryByText('A quien hay que llamar')).toBeNull();
    expect(dashboard).not.toHaveBeenCalled();
  });

  it('keeps the health of the stack visible', async () => {
    vi.spyOn(loansApi, 'loanDashboard').mockResolvedValue({
      out: 0,
      overdue: 0,
      dueToday: 0,
      available: 0,
      urgent: [],
    });

    renderHome('BIBLIOTECARIO');

    expect(await screen.findByText('Estado del stack')).toBeTruthy();
    expect(await screen.findByText('Operativa')).toBeTruthy();
  });
});