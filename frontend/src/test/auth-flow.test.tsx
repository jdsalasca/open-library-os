import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthProvider';
import { RequireRole } from '../routes/guards';
import { Login } from '../pages/Login';
import { ApiError } from '../api/client';

const me = {
  id: 1,
  email: 'admin@local',
  fullName: 'Administrador',
  role: 'ADMINISTRADOR' as const,
  active: true,
  mustChangePassword: false,
  authorities: ['users:read', 'users:write', 'users:roles'],
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderApp(initial = '/entrar') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initial]}>
        <AuthProvider>
          <Routes>
            <Route path="/entrar" element={<Login />} />
            <Route
              path="/privada"
              element={
                <RequireRole roles={['ADMINISTRADOR']}>
                  <p>Zona administrativa</p>
                </RequireRole>
              }
            />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('flujo de autenticacion', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('muestra el formulario de entrada cuando no hay sesion', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ token: 't', header: 'X-XSRF-TOKEN' })) // csrf
      .mockResolvedValueOnce(json({ code: 'unauthenticated' }, 401)); // me

    renderApp();

    expect(await screen.findByRole('heading', { name: 'Open Library OS' })).toBeInTheDocument();
    expect(screen.getByLabelText('Correo')).toBeInTheDocument();
  });

  it('inicia sesion y deja pasar a la zona protegida', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ token: 't', header: 'X-XSRF-TOKEN' })) // csrf
      .mockResolvedValueOnce(json({ code: 'unauthenticated' }, 401)) // me
      .mockResolvedValueOnce(json({ token: 't2', header: 'X-XSRF-TOKEN' })) // csrf del login
      .mockResolvedValueOnce(json(me)); // login

    renderApp('/entrar');

    await userEvent.type(screen.getByLabelText('Correo'), 'admin@local');
    await userEvent.type(screen.getByLabelText('Contrasena'), 'ChangeMe!2026');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    await waitFor(() => expect(screen.queryByLabelText('Correo')).not.toBeInTheDocument());
  });

  it('muestra el mensaje del servidor cuando las credenciales son invalidas', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ token: 't', header: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(json({ code: 'unauthenticated' }, 401))
      .mockResolvedValueOnce(json({ token: 't2', header: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(
        json({ detail: 'Correo o contrasena incorrectos.' }, 401),
      );

    renderApp('/entrar');

    await userEvent.type(screen.getByLabelText('Correo'), 'nadie@local');
    await userEvent.type(screen.getByLabelText('Contrasena'), 'malaclave');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByText('Correo o contrasena incorrectos.')).toBeInTheDocument();
  });

  it('impide el acceso a la zona restringida sin el rol necesario', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ token: 't', header: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(json({ ...me, role: 'LECTOR', authorities: ['catalog:read'] }));

    renderApp('/privada');

    await waitFor(() => expect(screen.queryByText('Zona administrativa')).not.toBeInTheDocument());
  });

  it('permite el acceso cuando el rol coincide', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ token: 't', header: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(json(me));

    renderApp('/privada');

    expect(await screen.findByText('Zona administrativa')).toBeInTheDocument();
  });

  it('trata un 403 de la API como error legible', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(json({ token: 't', header: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(json({ code: 'unauthenticated' }, 401))
      .mockResolvedValueOnce(json({ token: 't2', header: 'X-XSRF-TOKEN' }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'));

    renderApp('/entrar');

    await userEvent.type(screen.getByLabelText('Correo'), 'a@b.test');
    await userEvent.type(screen.getByLabelText('Contrasena'), 'x'.repeat(10));
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    await waitFor(() =>
      expect(screen.getByText('No se ha podido iniciar sesion.')).toBeInTheDocument(),
    );
  });
});

describe('ApiError', () => {
  it('expone el detalle y los errores de campo', () => {
    const error = new ApiError(400, {
      detail: 'Datos invalidos',
      fieldErrors: [{ field: 'email', message: 'Correo ya registrado' }],
    });

    expect(error.message).toBe('Datos invalidos');
    expect(error.fieldError('email')).toBe('Correo ya registrado');
    expect(error.fieldError('role')).toBeUndefined();
  });
});
