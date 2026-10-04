import { useQuery } from '@tanstack/react-query';
import { Badge, Card, CardBody, CardHeader, PageHead, Skeleton } from '../components';
import { getHealth } from '../api/system';
import { useAuth } from '../auth/auth-context';
import './Home.scss';

const ROADMAP = [
  { round: '2', title: 'Catalogo', detail: 'Libros, autores multiples, editoriales y categorias.' },
  { round: '3', title: 'Inventario', detail: 'Ejemplares con codigo de barras y ubicacion fisica.' },
  { round: '4', title: 'Prestamos', detail: 'Alta, devolucion, renovacion y reservas.' },
  { round: '5', title: 'ISBN', detail: 'Relleno automatico desde Open Library y Google Books.' },
  { round: '6', title: 'Mapa 3D', detail: 'Donde esta cada ejemplar, desde el movil.' },
];

export function Home() {
  const { user } = useAuth();

  const health = useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => getHealth(signal),
    refetchInterval: 30_000,
  });

  const up = health.data?.status === 'UP';

  return (
    <div className="home">
      <PageHead
        eyebrow={`Sesion de ${user?.fullName ?? ''}`}
        title="Tu biblioteca, en marcha"
        lead="Todo el estado vive en tu servidor Postgres: reinicia los contenedores cuando quieras y los datos seguiran ahi."
        actions={
          <Badge tone={up ? 'success' : 'warning'} dot>
            {health.isPending ? 'Comprobando' : health.isError ? 'API inaccesible' : 'Servicios activos'}
          </Badge>
        }
      />

      <div className="home__grid">
        <Card>
          <CardHeader
            title="Estado del sistema"
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
                <dt>Migraciones</dt>
                <dd>Flyway</dd>
              </div>
              <div>
                <dt>Sesiones</dt>
                <dd>En base de datos</dd>
              </div>
            </dl>
            <p className="home__note">
              Tu sesion se guarda en Postgres, no en memoria: puedes reiniciar el backend sin perder
              el acceso.
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Proximas rondas" subtitle="Entregables verificados, uno por ronda" />
          <CardBody>
            <ol className="home__roadmap">
              {ROADMAP.map((item) => (
                <li key={item.round}>
                  <span className="home__round">{item.round}</span>
                  <div>
                    <strong>{item.title}</strong>
                    <span>{item.detail}</span>
                  </div>
                </li>
              ))}
            </ol>
          </CardBody>
        </Card>
      </div>
    </div>
  );
}
