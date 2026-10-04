import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import { Button, EmptyState, ErrorState, Field } from '../components';

describe('Button', () => {
  it('exposes an accessible name and fires the handler', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Guardar libro</Button>);

    await userEvent.click(screen.getByRole('button', { name: 'Guardar libro' }));

    expect(onClick).toHaveBeenCalledOnce();
  });

  it('blocks interaction and announces busy state while loading', async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Guardar
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Guardar' });

    await userEvent.click(button);

    expect(onClick).not.toHaveBeenCalled();
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
  });

  it('renders as a link when given a destination', () => {
    render(
      <MemoryRouter>
        <Button to="/catalogo">Ver catalogo</Button>
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: 'Ver catalogo' })).toHaveAttribute(
      'href',
      '/catalogo',
    );
  });
});

describe('Field', () => {
  it('links label, hint and error to the control', () => {
    render(
      <Field label="ISBN" hint="13 digitos" error="ISBN no valido" value="" onChange={() => {}} />,
    );

    const input = screen.getByLabelText('ISBN');

    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('13 digitos ISBN no valido');
  });

  it('omits the error hook when there is no error', () => {
    render(<Field label="Titulo" value="Dune" onChange={() => {}} />);

    expect(screen.getByLabelText('Titulo')).not.toHaveAttribute('aria-invalid');
  });
});

describe('states', () => {
  it('empty state offers the recovery action', () => {
    render(<EmptyState title="Sin libros" description="Empieza importando un CSV." />);

    expect(screen.getByRole('heading', { name: 'Sin libros' })).toBeInTheDocument();
  });

  it('error state is announced as an alert and can retry', async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="No hay conexion" onRetry={onRetry} />);

    expect(screen.getByRole('alert')).toHaveTextContent('No hay conexion');
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});