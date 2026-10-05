import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReaderPicker } from '../components/ReaderPicker';
import * as api from '../api/loans';

function renderPicker() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ReaderPicker onSelect={() => {}} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('ReaderPicker', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('finds a reader by name and shows what they already have', async () => {
    const search = vi.spyOn(api, 'searchReaders').mockResolvedValue([
      { id: 7, email: 'bruno@demo.test', fullName: 'Bruno García', activeLoans: 2, overdue: 1 },
    ]);
    const user = userEvent.setup();
    renderPicker();

    await user.type(screen.getByLabelText(/Lector/i), 'bru');

    expect(await screen.findByText('Bruno García', {}, { timeout: 5000 })).toBeTruthy();
    // The desk needs this to know whether to warn before lending.
    expect(screen.getByText(/2 prestamos/i)).toBeTruthy();
    expect(screen.getByText(/1 vencido/i)).toBeTruthy();
    expect(search).toHaveBeenCalledWith('bru');
  });

  it('hands the id to the caller and then gets out of the way', async () => {
    vi.spyOn(api, 'searchReaders').mockResolvedValue([
      { id: 7, email: 'bruno@demo.test', fullName: 'Bruno García', activeLoans: 0, overdue: 0 },
    ]);
    const onSelect = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <ReaderPicker onSelect={onSelect} />
        </QueryClientProvider>
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/Lector/i), 'bru');
    await user.click(await screen.findByRole('button', { name: /Bruno/ }, { timeout: 5000 }));

    expect(onSelect).toHaveBeenCalledWith({
      id: 7,
      email: 'bruno@demo.test',
      fullName: 'Bruno García',
      activeLoans: 0,
      overdue: 0,
    });
    expect(screen.getByText(/Bruno García/)).toBeTruthy();
  });

  it('lets the librarian go back and pick somebody else', async () => {
    const bruno = { id: 7, email: 'bruno@demo.test', fullName: 'Bruno García', activeLoans: 0, overdue: 0 };
    vi.spyOn(api, 'searchReaders').mockResolvedValue([bruno]);
    const onSelect = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    const { rerender } = render(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <ReaderPicker onSelect={onSelect} />
        </QueryClientProvider>
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/Lector/i), 'bru');
    await user.click(await screen.findByRole('button', { name: /Bruno/ }, { timeout: 5000 }));

    // The parent now owns the choice, so it has to render it back.
    rerender(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <ReaderPicker onSelect={onSelect} selected={bruno} />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await user.click(screen.getByRole('button', { name: /Cambiar de lector/i }));

    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it('does not call the server once per keystroke', async () => {
    const search = vi.spyOn(api, 'searchReaders').mockResolvedValue([]);
    const user = userEvent.setup();
    renderPicker();

    await user.type(screen.getByLabelText(/Lector/i), 'brun');

    await waitFor(() => expect(search).toHaveBeenCalled(), { timeout: 5000 });
    // Four keys, and the whole word on the last call. The exact count depends on
    // how fast the keyboard delivers, which is not what this test is about.
    expect(search.mock.calls.length).toBeLessThan(4);
    expect(search).toHaveBeenLastCalledWith('brun');
  });

  it('says so when nobody matches, instead of an empty box', async () => {
    vi.spyOn(api, 'searchReaders').mockResolvedValue([]);
    const user = userEvent.setup();
    renderPicker();

    await user.type(screen.getByLabelText(/Lector/i), 'zzzz');

    expect(await screen.findByText(/sin resultados/i, {}, { timeout: 5000 })).toBeTruthy();
  });

  it('never searches with an empty box', async () => {
    const search = vi.spyOn(api, 'searchReaders').mockResolvedValue([]);
    renderPicker();

    await new Promise((r) => setTimeout(r, 400));
    expect(search).not.toHaveBeenCalled();
  });
});
