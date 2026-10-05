import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Badge, Card, CardBody, CardHeader, EmptyState, PageHead, Skeleton } from '../components';
import { loanDashboard } from '../api/loans';
import { getHealth } from '../api/system';
import { useAuth } from '../auth/auth-context';
import { LibrarySummary } from '../components/LibrarySummary';
import './Home.scss';

/**
 * The first screen anybody opens.
 *
 * It used to greet the reader with the state of the database and a list of the
 * rounds already finished. A librarian does not need either: they need to know
 * who is late. So the numbers that are actionable come first, and the stack
 * health stays as the quiet footnote it always was.
 */
export function Home() {
  const { user } = useAuth();
  const isStaff = user?.authorities?.includes('loans:operate');

  const health = useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => getHealth(signal),
    refetchInterval: 30_000,
  });

  const dashboard = useQuery({
    queryKey: ['loans', 'dashboard'],
    queryFn: loanDashboard,
    enabled: Boolean(isStaff),
    refetchInterval: 60_000,
  });

  const up = health.data?.status === 'UP';

  return (
    <div className="home">
      <PageHead
        eyebrow={`Sesion de ${user?.fullName ?? ''}`}
        title={greeting(user?.fullName)}
        lead={
          isStaff
            ? 'Lo que hay que mirar hoy, antes de que nadie entre por la puerta.'
            : 'Tus prestamos, tus reservas y donde encontrar cada libro.'
        }
        actions={
          <Badge tone={up ? 'success' : 'warning'} dot>
            {health.isPending ? 'Comprobando' : health.isError ? 'API inaccesible' : 'Servicios activos'}
          </Badge>
        }
      />

      {isStaff ? <StaffBoard dashboard={dashboard} /> : <ReaderBoard />}

      <div className={isStaff ? 'home__below' : undefined}>
        <LibrarySummary />
      </div>

      <Card className="home__stack">
        <CardHeader
          title="Estado del stack"
          subtitle="Verificado contra la API en cada carga"
          actions={
            health.isError ? (
              <Badge tone="danger">Sin conexion</Badge>
            ) : (
              <Badge tone={up ? 'success' : 'warning'}>{health.data?.status ?? '...'}</Badge>
            )
          }
        />
        <CardBody>
          <dl className="home__facts">
            <div>
              <dt>API</dt>
              <dd>
                {health.isPending ? (
                  <Skeleton width="6rem" />
                ) : health.isError ? (
                  'Inaccesible'
                ) : (
                  'Operativa'
                )}
              </dd>
            </div>
            <div>
              <dt>Base de datos</dt>
              <dd>
                {health.isPending ? (
                  <Skeleton width="6rem" />
                ) : (
                  (health.data?.components?.db?.status ?? 'UP')
                )}
              </dd>
            </div>
            <div>
              <dt>Sesiones</dt>
              <dd>En base de datos</dd>
            </div>
          </dl>
        </CardBody>
      </Card>
    </div>
  );
}

function StaffBoard({
  dashboard,
}: {
  dashboard: ReturnType<typeof useQuery<Awaited<ReturnType<typeof loanDashboard>>>>;
}) {
  const data = dashboard.data;
  const error = dashboard.isError;
  const overdue = data?.overdue ?? 0;

  return (
    <>
      <div className="home__stats">
        <Stat label="Fuera" value={data?.out} hint="prestamos activos" />
        <Stat
          label="Vencidos"
          value={data?.overdue}
          hint={overdue > 0 ? 'hay que perseguir a alguien' : 'nadie debe nada'}
          tone={overdue > 0 ? 'bad' : 'calm'}
        />
        <Stat label="Para hoy" value={data?.dueToday} hint="se devuelven hoy" />
        <Stat label="En estanteria" value={data?.available} hint="ejemplares libres" />
      </div>

      <Card>
        <CardHeader
          title="A quien hay que llamar"
          subtitle="Los mas atrasados primero"
          actions={
            <Link className="home__link" to="/prestamos">
              Ir al mostrador
            </Link>
          }
        />
        <CardBody>
          {dashboard.isPending && <Skeleton height="4rem" />}
          {error && (
            <p className="home__note">
              No se pudieron leer los prestamos. El mostrador sigue funcionando.
            </p>
          )}
          {data && data.urgent.length === 0 && (
            <EmptyState
              title="Nadie debe nada"
              description="Todos los prestamos estan dentro de plazo."
            />
          )}
          {data && data.urgent.length > 0 && (
            <ul className="home__urgent">
              {data.urgent.map((loan) => (
                <li key={`${loan.readerEmail}-${loan.bookTitle}`}>
                  <span className="home__late">{loan.daysLate} d</span>
                  <div>
                    <strong>{loan.readerName}</strong>
                    <span>{loan.bookTitle}</span>
                  </div>
                  <a href={`mailto:${loan.readerEmail}`}>{loan.readerEmail}</a>
                </li>
              ))}
            </ul>
          )}
        </CardBody>
      </Card>
    </>
  );
}

function ReaderBoard() {
  return (
    <Card>
      <CardHeader title="Tu rincon" subtitle="Sin salir de aqui" />
      <CardBody>
        <p className="home__note">
          Mira lo que tienes prestado, lo que has devuelto y lo que estas esperando en{' '}
          <Link className="home__link" to="/mi-biblioteca">
            Mi biblioteca
          </Link>
          . Para encontrar un libro en la sala, el{' '}
          <Link className="home__link" to="/mapa">
            mapa
          </Link>{' '}
          dice donde esta la estanteria.
        </p>
      </CardBody>
    </Card>
  );
}

function Stat({
  label,
  value,
  hint,
  tone = 'calm',
}: {
  label: string;
  value?: number;
  hint: string;
  tone?: 'calm' | 'bad';
}) {
  return (
    <Card className={`home__stat home__stat--${tone}`}>
      <CardBody>
        <span className="home__stat-label">{label}</span>
        <strong className="home__stat-value">{value ?? '...'}</strong>
        <span className="home__stat-hint">{hint}</span>
      </CardBody>
    </Card>
  );
}

function greeting(name?: string) {
  if (!name) return 'Tu biblioteca';
  const hour = new Date().getHours();
  const part = hour < 13 ? 'Buenos dias' : hour < 21 ? 'Buenas tardes' : 'Buenas noches';
  return `${part}, ${name.split(' ')[0]}`;
}