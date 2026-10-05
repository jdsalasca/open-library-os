import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { Login } from '../pages/Login';
import * as authHook from '../auth/auth-context';
import * as api from '../api/system';

function renderLogin() {
  vi.spyOn(authHook, 'useAuth').mockReturnValue({
    user: null,
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
  } as unknown as ReturnType<typeof authHook.useAuth>);

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <Login />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('pantalla de acceso', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('greets you with the name of your own library', async () => {
    vi.spyOn(api, 'libraryProfile').mockResolvedValue({ name: 'Biblioteca del Barrio' });

    renderLogin();

    expect(await screen.findByText(/Biblioteca del Barrio/)).toBeTruthy();
  });

  it('still lets you in when the name cannot be read', async () => {
    vi.spyOn(api, 'libraryProfile').mockRejectedValue(new Error('sin red'));

    renderLogin();

    // A name is decoration: it must never block somebody from signing in.
    expect(await screen.findByLabelText('Correo')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeTruthy();
  });
});