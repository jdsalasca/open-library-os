import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '../api/client';
import { dueLabel, dueTone, myLibrary, renewMyLoan } from '../api/myLibrary';
import { cancelReservation, REFUSAL_TEXT } from '../api/loans';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHead,
} from '../components';
import { useAuth } from '../auth/auth-context';
import './MyLibrary.scss';

/**
 * What a card holder sees: the books they have, the ones they have finished, and
 * where they stand in the queues. No ids, no staff vocabulary.
 */
export function MyLibrary_() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const mine = useQuery({ queryKey: ['my-library'], queryFn: myLibrary });

  const cancel = useMutation({
    mutationFn: cancelReservation,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['my-library'] }),
  });

// Coming back for a book that is due tomorrow is one of the commonest reasons
  // to walk into a library. The same rules apply; only the trip is saved.
  const renew = useMutation({
    mutationFn: renewMyLoan,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['my-library'] }),
  });

  const failure = (error: unknown) => {
    const api = error as ApiError;
    return REFUSAL_TEXT[api.code ?? ''] ?? api.message;
  };

  const loans = mine.data?.loans ?? [];
  const history = mine.data?.history ?? [];
  const reservations = mine.data?.reservations ?? [];

  return (
    <div className="mine">
<PageHead
        eyebrow="Mi biblioteca"
        title={user?.fullName ? `Hola, ${user.fullName.split(' ')[0]}` : 'Mi biblioteca'}
        lead="Lo que tienes prestado, lo que has devuelto y lo que estas esperando."
        actions={
          loans.length > 0 ? (
            <Link className="mine__slip-link" to="/mi-biblioteca/resguardo">
              Resguardo para el mostrador
            </Link>
          ) : undefined
        }
      />

      {mine.isPending && <LoadingState label="Cargando tu biblioteca" rows={5} />}
      {mine.isError && (
        <ErrorState
          message="No se ha podido cargar tu biblioteca."
          onRetry={() => void mine.refetch()}
        />
      )}

      {mine.data && (
        <>
          <section className="mine__section">
            <h2 className="mine__heading">
              Prestados ahora
              <Badge tone="neutral">{loans.length}</Badge>
            </h2>
            <Card flush>
              {loans.length === 0 ? (
                <EmptyState
                  title="No tienes nada prestado"
                  description="Cuando te lleves un libro aparecera aqui con su fecha de devolucion."
                  icon="loans"
                />
              ) : (
                <ul className="mine__loans">
                  {loans.map((loan) => (
                    <li key={loan.id}>
                      <div className="mine__book">
                        <a href={`/catalogo/${loan.bookId}`} className="mine__title">
                          {loan.bookTitle ?? `Libro ${loan.bookId}`}
                        </a>
                        <span className="mine__code">{loan.copyCode}</span>
                      </div>
                      <div className="mine__due">
                        <Badge tone={dueTone(loan.dueAt)} dot>
                          {dueLabel(loan.dueAt)}
                        </Badge>
                        <span className="mine__date">
                          {new Date(loan.dueAt).toLocaleDateString('es-ES')}
                        </span>
                        {loan.overdue ? null : (
                          <Button
                            size="sm"
                            variant="secondary"
                            loading={renew.isPending && renew.variables === loan.id}
                            onClick={() => renew.mutate(loan.id)}
                          >
                            Renovar
                          </Button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
            {renew.isSuccess && (
              <p className="mine__ok" role="status">
                Renovado hasta el{' '}
                {new Date(renew.data!.dueAt).toLocaleDateString('es-ES')}.
              </p>
            )}
            {renew.isError && (
              <p className="mine__error" role="alert">
                {failure(renew.error)}
              </p>
            )}
          </section>

          {reservations.length > 0 && (
            <section className="mine__section">
              <h2 className="mine__heading">
                Esperando
                <Badge tone="neutral">{reservations.length}</Badge>
              </h2>
              <Card flush>
                <ul className="mine__loans">
                  {reservations.map((reservation) => (
                    <li key={reservation.id}>
                      <div className="mine__book">
                        <a href={`/catalogo/${reservation.bookId}`} className="mine__title">
                          {reservation.bookTitle ?? `Libro ${reservation.bookId}`}
                        </a>
                        <span className="mine__code">
                          Puesto {reservation.place} de {reservation.queueLength}
                        </span>
                      </div>
                      <div className="mine__due">
                        {reservation.availableNow ? (
                          <Badge tone="success" dot>
                            Ya esta en la estanteria
                          </Badge>
                        ) : (
                          <Button
                            size="sm"
                            variant="secondary"
                            loading={cancel.isPending}
                            onClick={() => cancel.mutate(reservation.id)}
                          >
                            Salir de la cola
                          </Button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
                {cancel.isError && (
                  <p className="mine__error" role="alert">
                    {failure(cancel.error)}
                  </p>
                )}
              </Card>
            </section>
          )}

          <section className="mine__section">
            <h2 className="mine__heading">
              Historial
              <Badge tone="neutral">{history.length}</Badge>
            </h2>
            <Card flush>
              {history.length === 0 ? (
                <EmptyState
                  title="Sin historial todavia"
                  description="Los libros que devuelvas se quedaran aqui."
                  icon="catalog"
                />
              ) : (
                <ul className="mine__loans mine__loans--history">
                  {history.map((loan) => (
                    <li key={loan.id}>
                      <div className="mine__book">
                        <span className="mine__title">
                          {loan.bookTitle ?? `Libro ${loan.bookId}`}
                        </span>
                        <span className="mine__code">{loan.copyCode}</span>
                      </div>
                      <span className="mine__date">
                        {loan.returnedAt
                          ? `Devuelto el ${new Date(loan.returnedAt).toLocaleDateString('es-ES')}`
                          : 'Devuelto'}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </section>

          <Card className="mine__help">
            <CardHeader title="Como funciona" subtitle="Tres cosas y ya esta" />
            <CardBody>
              <ol className="mine__steps">
                <li>
                  <strong>Busca el libro</strong> en el catalogo. Si esta disponible, pidelo en el
                  mostrador con tu carnet.
                </li>
                <li>
                  <strong>Si no hay ninguno</strong>, reservalo desde la ficha del libro y te
                  avisaremos en cuanto llegue uno.
                </li>
                <li>
                  <strong>Devuelvelo a tiempo.</strong> Un libro vencido te bloquea el prestamo
                  siguiente.
                </li>
              </ol>
            </CardBody>
          </Card>
        </>
      )}
    </div>
  );
}
