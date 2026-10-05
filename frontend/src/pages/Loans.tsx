import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '../api/client';
import { lookupCopy, type Copy } from '../api/inventory';
import {
  REFUSAL_TEXT,
borrow,
  downloadOverdueCsv,
  listLoans,
  loanSettings,
  renewLoan,
  reservationQueue,
  returnLoan,
  type DeskReader,
  type Loan,
  type LoanState,
} from '../api/loans';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  DataTable,
  EmptyState,
  ErrorState,
  Field,
  LoadingState,
  PageHead,
  Pagination,
  type Column,
} from '../components';
import { ReaderPicker } from '../components/ReaderPicker';
import { useAuth } from '../auth/auth-context';
import { useScanner } from '../hooks/useScanner';
import './Loans.scss';

const SIZE = 15;

/** The desk screen: big targets, one field to focus, the reader's card in view. */
export function Loans() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [reader, setReader] = useState<DeskReader | null>(null);
  const readerId = reader ? String(reader.id) : '';
  const [state, setState] = useState<LoanState>('OPEN');
  const [manual, setManual] = useState('');
  const [notice, setNotice] = useState<{ tone: 'ok' | 'ko'; text: string } | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [copies, setCopies] = useState<Copy[]>([]);
  const manualRef = useRef<HTMLInputElement | null>(null);

  const settings = useQuery({ queryKey: ['loan-settings'], queryFn: loanSettings });
  const loans = useQuery({
    queryKey: ['loans', { state, page, readerId }],
    queryFn: () =>
      listLoans({
        state,
        readerId: readerId ? Number(readerId) : undefined,
        page,
        size: SIZE,
      }),
    placeholderData: (previous) => previous,
  });
  const queue = useQuery({ queryKey: ['reservation-queue'], queryFn: reservationQueue });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['loans'] });
    void queryClient.invalidateQueries({ queryKey: ['reservation-queue'] });
  };

  const say = (tone: 'ok' | 'ko', text: string) => setNotice({ tone, text });

  // A scanned or typed code arrives here: resolve it and list what was found.
  const onCode = async (code: string) => {
    setLookupError(null);
    try {
      const found = await lookupCopy(code);
      setCopies(found ? [found] : []);
      say('ok', `Ejemplar ${found.code} · ${found.bookTitle ?? ''}`);
    } catch (error) {
      setCopies([]);
      setLookupError(
        error instanceof ApiError ? error.message : `No se ha podido leer "${code}".`,
      );
    }
  };

  const { wedgeActive, cameraError, cameraReady, videoRef, startCamera, stopCamera } =
    useScanner(onCode);

  const doBorrow = useMutation({
    mutationFn: (copyId: number) => borrow(copyId, Number(readerId)),
    onSuccess: (loan) => {
      say('ok', `Prestado ${loan.copyCode} hasta el ${new Date(loan.dueAt).toLocaleDateString('es-ES')}`);
      setCopies([]);
      refresh();
      manualRef.current?.focus();
    },
    onError: (error) => {
      const api = error as ApiError;
      say('ko', api.fieldErrors[0]?.message ?? REFUSAL_TEXT[api.code ?? ''] ?? api.message);
    },
  });

  const doRenew = useMutation({
    mutationFn: renewLoan,
    onSuccess: () => {
      say('ok', 'Prestamo renovado');
      refresh();
    },
    onError: (error) => {
      const api = error as ApiError;
      say('ko', REFUSAL_TEXT[api.code ?? ''] ?? api.message);
    },
  });

  const doReturn = useMutation({
    mutationFn: returnLoan,
    onSuccess: (loan) => {
      say('ok', `Devuelto ${loan.copyCode ?? ''}`);
      refresh();
    },
    onError: (error) => say('ko', (error as ApiError).message),
  });

  // Keep the page in view after every mutation: the desk never wants to hunt.
  useEffect(() => {
    if (notice) manualRef.current?.focus();
  }, [notice]);

  const columns: Column<Loan>[] = [
    {
      key: 'copy',
      header: 'Ejemplar',
      render: (loan) => (
        <div className="loans__copy">
          <span className="loans__code">{loan.copyCode ?? `#${loan.copyId}`}</span>
          <span className="loans__book">{loan.bookTitle ?? ''}</span>
        </div>
      ),
    },
    {
      key: 'reader',
      header: 'Lector',
      render: (loan) => <span className="loans__muted">{loan.readerEmail ?? loan.readerId}</span>,
      hideOnMobile: true,
    },
    {
      key: 'due',
      header: 'Vence',
      render: (loan) => (
        <span className={loan.overdue ? 'loans__due loans__due--late' : 'loans__due'}>
          {new Date(loan.dueAt).toLocaleDateString('es-ES')}
          {loan.renewals > 0 && <span className="loans__renewals">+{loan.renewals}</span>}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Estado',
      // The badge repeats what the due date already says in red on a phone, and
      // hiding it is what keeps the return button inside the viewport.
      hideOnMobile: true,
      render: (loan) => {
        if (loan.returnedAt) return <Badge tone="neutral">Devuelto</Badge>;
        return loan.overdue ? (
          <Badge tone="danger" dot>Vencido</Badge>
        ) : (
          <Badge tone="success" dot>En plazo</Badge>
        );
      },
    },
    {
      key: 'actions',
      header: 'Acciones',
      align: 'end',
      hideOnMobile: state === 'CLOSED',
      render: (loan) => (
        <div className="loans__actions">
          <Button size="sm" variant="secondary" onClick={() => doRenew.mutate(loan.id)}>
            Renovar
          </Button>
          <Button size="sm" onClick={() => doReturn.mutate(loan.id)}>
            Devolver
          </Button>
        </div>
      ),
    },
  ];

  const canOperate = Boolean(user?.authorities?.includes('loans:operate'));

  // Calling twenty people works better on paper than on a table that only shows
  // fifteen rows at a time.
  const csv = useMutation({
    mutationFn: downloadOverdueCsv,
    onSuccess: () => setNotice({ tone: 'ok', text: 'CSV descargado.' }),
    onError: () =>
      setNotice({ tone: 'ko', text: 'No se pudo generar el CSV de vencidos.' }),
  });

  return (
    <div className="loans">
      <PageHead
        eyebrow="Mostrador"
        title="Prestamos"
        lead="Escanea el codigo del ejemplar y el carnet del lector. El escaner USB funciona solo: dispara y pulsa Enter."
actions={
          <>
            {settings.data && (
              <p className="loans__policy">
                {settings.data.loanDays} dias · max {settings.data.readerLimit} por lector ·{' '}
                {settings.data.maxRenewals} renovaciones
              </p>
            )}
            {canOperate && (
              <Button
                variant="secondary"
                loading={csv.isPending}
                onClick={() => csv.mutate()}
              >
                Descargar vencidos (CSV)
              </Button>
            )}
          </>
        }
      />

      <div className="loans__desk">
        <Card className="loans__scan">
          <CardHeader
            title="Prestamo rapido"
            subtitle="Un campo para el lector, uno para el ejemplar"
          />
          <CardBody>
            <div className="loans__desk-fields">
              <ReaderPicker onSelect={setReader} selected={reader} />
              <Field
                ref={manualRef}
                label="Codigo del ejemplar"
                placeholder="OL-0000000001"
                value={manual}
                onChange={(event) => setManual(event.target.value)}
                hint={
                  wedgeActive
                    ? 'Escaner detectado'
                    : 'Escanea, escribe el codigo y pulsa Enter'
                }
              />
            </div>

            <div className="loans__desk-actions">
              <Button
                disabled={manual.trim().length < 3}
                onClick={() => {
                  void onCode(manual.trim());
                  setManual('');
                }}
              >
                Buscar ejemplar
              </Button>
              <Button variant="secondary" onClick={() => void startCamera()}>
                Usar camara
              </Button>
              {cameraReady && (
                <Button variant="ghost" onClick={stopCamera}>
                  Cerrar camara
                </Button>
              )}
            </div>

            {cameraError && (
              <p className="loans__hint loans__hint--warn">{cameraError}</p>
            )}
            {cameraReady && (
              <video className="loans__video" ref={videoRef} muted playsInline />
            )}

            {lookupError && (
              <p className="loans__notice loans__notice--ko" role="alert">
                {lookupError}
              </p>
            )}

            {copies.length > 0 && (
              <ul className="loans__found">
                {copies.map((copy) => (
                  <li key={copy.id}>
                    <div>
                      <span className="loans__code">{copy.code}</span>
                      <span className="loans__book">{copy.bookTitle}</span>
                      <span className="loans__muted">
                        {copy.locationCode ?? 'Sin ubicacion'} · {copy.status}
                      </span>
                    </div>
                    <Button
                      disabled={!readerId || copy.status !== 'DISPONIBLE'}
                      loading={doBorrow.isPending}
                      onClick={() => doBorrow.mutate(copy.id)}
                    >
                      Prestar
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            {!readerId && copies.length > 0 && (
              <p className="loans__hint">Escribe el lector para completar el prestamo.</p>
            )}

            {notice && (
              <p
                className={`loans__notice loans__notice--${notice.tone}`}
                role="status"
              >
                {notice.text}
              </p>
            )}
          </CardBody>
        </Card>

        <Card className="loans__queue">
          <CardHeader
            title="Cola de reservas"
            subtitle="Por orden de llegada"
            actions={
              queue.isLoading ? (
                <LoadingState rows={1} />
              ) : (
                <Badge tone="neutral">{queue.data?.filter((r) => r.open).length ?? 0}</Badge>
              )
            }
          />
          <CardBody>
            {queue.data?.filter((r) => r.open).length === 0 && (
              <EmptyState
                title="Nadie espera"
                description="No hay reservas pendientes."
                icon="inbox"
              />
            )}
            {queue.data && queue.data.filter((r) => r.open).length > 0 && (
              <ol className="loans__waiting">
                {queue.data
                  .filter((r) => r.open)
                  .map((reservation, index) => (
                    <li key={reservation.id}>
                      <span className="loans__place">{index + 1}</span>
                      <span className="loans__book">{reservation.bookTitle ?? `Libro ${reservation.bookId}`}</span>
                      <span className="loans__muted">
                        {new Date(reservation.createdAt).toLocaleDateString('es-ES')}
                      </span>
                    </li>
                  ))}
              </ol>
            )}
          </CardBody>
        </Card>
      </div>

      <Card flush>
        <div className="loans__tabs">
          {(['OPEN', 'CLOSED'] as const).map((option) => (
            <Button
              key={option}
              size="sm"
              variant={state === option ? 'primary' : 'ghost'}
              aria-pressed={state === option}
              onClick={() => {
                setState(option);
                setPage(0);
              }}
            >
              {option === 'OPEN' ? 'Prestamos activos' : 'Historial'}
            </Button>
          ))}
        </div>
        {loans.isPending && <LoadingState label="Cargando prestamos" rows={6} />}
        {loans.isError && (
          <ErrorState
            message="No se ha podido cargar la lista de prestamos."
            onRetry={() => void loans.refetch()}
          />
        )}
        {loans.data && loans.data.totalElements === 0 && (
          <EmptyState
            title="Sin prestamos"
            description="Los prestamos que hagas apareceran aqui."
            icon="inbox"
          />
        )}
        {loans.data && loans.data.totalElements > 0 && (
          <>
            <DataTable
              caption={state === 'OPEN' ? 'Prestamos activos' : 'Historial de prestamos'}
              columns={canOperate ? columns : columns.slice(0, 4)}
              rows={loans.data.content}
              rowKey={(loan) => loan.id}
            />
            <Pagination
              page={loans.data.page}
              totalPages={loans.data.totalPages}
              totalElements={loans.data.totalElements}
              onPage={setPage}
            />
          </>
        )}
      </Card>
    </div>
  );
}
