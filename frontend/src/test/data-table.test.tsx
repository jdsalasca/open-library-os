import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { AuthValue } from '../auth/auth-context';
import { DataTable, Pagination, type Column } from '../components/DataTable';

const admin: AuthValue = {
  user: {
    id: 1,
    email: 'admin@local',
    fullName: 'Administrador',
    role: 'ADMINISTRADOR',
    active: true,
    mustChangePassword: false,
    authorities: ['catalog:read', 'catalog:write'],
  },
  loading: false,
  can: () => true,
  hasRole: () => true,
  login: async () => admin.user!,
  logout: async () => {},
  refresh: async () => {},
};

describe('DataTable', () => {
  const rows = [
    { id: 1, title: 'Dune', author: 'Frank Herbert' },
    { id: 2, title: 'Neuromancer', author: 'William Gibson' },
  ];

  const columns: Column<(typeof rows)[number]>[] = [
    { key: 'title', header: 'Titulo', render: (row) => row.title },
    { key: 'author', header: 'Autor', render: (row) => row.author },
  ];

  it('renders an accessible table with a caption and row headers', () => {
    render(<DataTable caption="Libros" columns={columns} rows={rows} rowKey={(r) => r.id} />);

    expect(screen.getByRole('table', { name: 'Libros' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Titulo' })).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getByText('Neuromancer')).toBeInTheDocument();
  });

  it('opens a row on click and on Enter', async () => {
    const onRowClick = vi.fn();
    render(
      <DataTable
        caption="Libros"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        onRowClick={onRowClick}
      />,
    );

    const row = screen.getByText('Dune').closest('tr')!;
    await userEvent.click(row);
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);

    onRowClick.mockClear();
    row.focus();
    await userEvent.keyboard('{Enter}');
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });

  it('shows the empty slot instead of a bare table', () => {
    render(
      <DataTable
        caption="Libros"
        columns={columns}
        rows={[]}
        rowKey={(r) => r.id}
        empty={<p>No hay libros</p>}
      />,
    );

    expect(screen.getByText('No hay libros')).toBeInTheDocument();
  });
});

describe('Pagination', () => {
  it('moves between pages and disables the edges', async () => {
    const onPage = vi.fn();
    render(
      <Pagination page={1} totalPages={3} totalElements={50} onPage={onPage} />,
    );

    expect(screen.getByText('21-40 de 50')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pagina anterior' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Pagina siguiente' })).toBeEnabled();

    await userEvent.click(screen.getByRole('button', { name: 'Pagina siguiente' }));
    expect(onPage).toHaveBeenCalledWith(2);
  });

  it('disables the next button on the last page', () => {
    render(<Pagination page={2} totalPages={3} totalElements={50} onPage={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Pagina siguiente' })).toBeDisabled();
  });

  it('renders nothing when there are no results', () => {
    const { container } = render(
      <Pagination page={0} totalPages={0} totalElements={0} onPage={vi.fn()} />,
    );

    expect(container).toBeEmptyDOMElement();
  });
});
