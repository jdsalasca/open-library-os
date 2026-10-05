import { useQuery } from '@tanstack/react-query';

import { backupStatus } from '../api/admin';
import { Badge, Card, CardBody, CardHeader, Skeleton } from './index';
import './backup-card.scss';

/**
 * Are my backups running?
 *
 * <p>This app sells the promise that your data is yours and survives. The person
 * who has to answer "is that still true?" should not have to read container logs.
 *
 * <p>Nothing here triggers a backup: that is the backup container's job, and the
 * restore drill is a script on purpose. This only reports what is on disk.
 */
export function BackupCard() {
  const status = useQuery({
    queryKey: ['backup-status'],
    queryFn: backupStatus,
    refetchInterval: 60_000,
  });

  const data = status.data;

  return (
    <Card>
      <CardHeader
        title="Respaldos"
        subtitle="Copias de la base de datos, una por dia"
        actions={
          data ? (
            <Badge tone={data.healthy ? 'success' : 'danger'} dot>
              {data.healthy ? 'Al dia' : 'Requieren atencion'}
            </Badge>
          ) : undefined
        }
      />
      <CardBody>
        {status.isPending && <Skeleton height="4rem" />}

        {status.isError && (
          <p className="backup__error" role="alert">
            No se pudo leer el volumen de respaldos. Puede que el contenedor
            <code> backup </code> no este levantando.
          </p>
        )}

        {data && (
          <>
            {data.healthy ? (
              <p className="backup__ok">{data.message}</p>
            ) : (
              <p className="backup__error" role="alert">
                {data.message}
              </p>
            )}

            {data.newest && (
              <dl className="backup__facts">
                <div>
                  <dt>Ultimo fichero</dt>
                  <dd>
                    <code>{data.newest.name}</code>
                  </dd>
                </div>
                <div>
                  <dt>Tamano</dt>
                  <dd>{size(data.newest.bytes)}</dd>
                </div>
                <div>
                  <dt>Guardados</dt>
                  <dd>{data.dumps}</dd>
                </div>
              </dl>
            )}

            <p className="backup__note">
              Restaurar y comprobar un respaldo es
              <code> sh scripts/verify-restore.sh</code>: un fichero que no se ha
              restaurado nunca es una suposicion.
            </p>
          </>
        )}
      </CardBody>
    </Card>
  );
}

/** Spanish thousands separator, because this is a number somebody compares. */
function size(bytes: number) {
  const mb = bytes / 1_048_576;
  return mb >= 1 ? `${mb.toFixed(1).replace('.', ',')} MB` : `${Math.round(bytes / 1024)} kB`;
}