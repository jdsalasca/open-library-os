import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { LoanSettings } from '../components/LoanSettings';
import * as api from '../api/loans';

const current = { loanDays: 14, readerLimit: 5, maxRenewals: 2 };

function renderForm(onSaved?: () => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <LoanSettings onSaved={onSaved} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('ajustes de prestamos', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'loanSettings').mockResolvedValue(current);
  });

  it('starts from the policy actually in use', async () => {
    renderForm();

    expect(await screen.findByLabelText(/dias de prestamo/i)).toHaveValue(14);
    expect(screen.getByLabelText(/prestamos por lector/i)).toHaveValue(5);
    expect(screen.getByLabelText(/renovaciones/i)).toHaveValue(2);
  });

  it('sends the three numbers together and confirms', async () => {
    const save = vi.spyOn(api, 'saveLoanSettings').mockResolvedValue({
      loanDays: 21,
      readerLimit: 8,
      maxRenewals: 1,
    });
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderForm(onSaved);

    await screen.findByLabelText(/dias de prestamo/i);
    await user.clear(screen.getByLabelText(/dias de prestamo/i));
    await user.type(screen.getByLabelText(/dias de prestamo/i), '21');
    await user.clear(screen.getByLabelText(/prestamos por lector/i));
    await user.type(screen.getByLabelText(/prestamos por lector/i), '8');
    await user.click(screen.getByRole('button', { name: /guardar/i }));

    // react-query also hands the mutation a context object; only the body matters here.
    expect(save.mock.calls[0][0]).toEqual({ loanDays: 21, readerLimit: 8, maxRenewals: 2 });
    expect(await screen.findByText(/guardado/i)).toBeTruthy();
    expect(onSaved).toHaveBeenCalled();
  });

  it('does not let the administrator save nonsense', async () => {
    const save = vi.spyOn(api, 'saveLoanSettings');
    const user = userEvent.setup();
    renderForm();

    await screen.findByLabelText(/dias de prestamo/i);
    await user.clear(screen.getByLabelText(/dias de prestamo/i));
    await user.type(screen.getByLabelText(/dias de prestamo/i), '0');
    await user.click(screen.getByRole('button', { name: /guardar/i }));

    // Lending books for no days at all would break the shelves.
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeTruthy();
  });

  it('explains a refusal from the server in place', async () => {
    vi.spyOn(api, 'saveLoanSettings').mockRejectedValue(
      Object.assign(new Error('Los dias de prestamo van de 1 a 365. (loanDays)'), { status: 400 }),
    );
    const user = userEvent.setup();
    renderForm();

    await screen.findByLabelText(/dias de prestamo/i);
    await user.click(screen.getByRole('button', { name: /guardar/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/1 a 365/);
  });
});