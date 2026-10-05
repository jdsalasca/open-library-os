import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { daysLeft, dueLabel, myLibrary } from '../api/myLibrary';
import { Button, EmptyState, ErrorState, LoadingState } from '../components';
import { useAuth } from '../auth/auth-context';
import './DueSlip.scss';

/**
 * The slip a reader takes to the desk.
 *
 * The app sends no email on purpose: no accounts, no third parties, no external
 * service. So when a book is late, the reader has to be able to *show* it, and
 * the desk has to be able to scan it back in. This is that piece of paper: the
 * copy codes are the ones the label printer already prints.
 *
 * It is a normal screen first and a print second, because a slip you cannot read
 * on the phone is a slip nobody opens.
 */
export function DueSlip() {
  const { user } = useAuth();
  // Fixed once per visit: this is the date the slip was written on, and asking
  // the clock on every render would be both impure and wrong.
  const [writtenOn] = useState(() => new Date());
  const mine = useQuery({ queryKey: ['my-library'], queryFn: myLibrary });

  if (mine.isPending) {
    return <LoadingState label="Cargando tus prestamos" rows={3} />;
  }
  if (mine.isError) {
    return (
      <ErrorState message="No se han podido cargar tus prestamos." onRetry={() => void mine.refetch()} />
    );
  }

  const loans = mine.data?.loans ?? [];
  const late = loans.filter((loan) => loan.overdue);
  // Days is the number the desk and the reader argue about, not "1 of 2".
  const worstDays = late.reduce((worst, loan) => Math.max(worst, -daysLeft(loan.dueAt)), 0);

  return (
    <div className="slip">
      <div className="slip__bar">
        <Button onClick={() => window.print()}>Imprimir</Button>
        <Button variant="ghost" onClick={() => window.history.back()}>
          Volver
        </Button>
      </div>

      <article className="slip__paper">
        <header className="slip__head">
          <div>
            {/* Whose slip it is: the desk takes it from a hand, not from an id. */}
            <h1>Prestamos de {user?.fullName ?? 'este lector'}</h1>
            <p className="slip__code">{user?.email}</p>
          </div>
          <p className="slip__when">{writtenOn.toLocaleDateString('es-ES')}</p>
        </header>

        {late.length > 0 && (
          <p className="slip__late" role="status">
            {late.length === 1
              ? `Tienes 1 libro vencido hace ${worstDays} dias.`
              : `Tienes ${late.length} libros vencidos, el mas antiguo hace ${worstDays} dias.`}
          </p>
        )}

        {loans.length === 0 ? (
          <EmptyState
            title="No tienes nada pendiente"
            description="Cuando te lleves un libro aparecera aqui con su codigo."
            icon="loans"
          />
        ) : (
          <table className="slip__table">
            <caption className="visually-hidden">
              Libros prestados con su codigo y su fecha de devolucion
            </caption>
            <thead>
              <tr>
                <th scope="col">Libro</th>
                <th scope="col">Codigo</th>
                <th scope="col">Devolver</th>
              </tr>
            </thead>
            <tbody>
              {loans.map((loan) => (
                <tr key={loan.id} data-overdue={loan.overdue || undefined}>
                  <th scope="row">{loan.bookTitle ?? `Libro ${loan.bookId}`}</th>
                  <td>
                    <code>{loan.copyCode}</code>
                  </td>
                  <td>
                    {dueLabel(loan.dueAt)}
                    <span className="slip__date">
                      {new Date(loan.dueAt).toLocaleDateString('es-ES')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </article>
    </div>
  );
}