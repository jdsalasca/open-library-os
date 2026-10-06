import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '../api/client';
import {
  ROLE_LABEL,
  ROLES,
  createUser,
  importReaders,
  listUsers,
  updateUser,
  type ReaderImportReport,
  type Role,
} from '../api/users';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  ErrorState,
  Field,
  LoadingState,
  PageHead,
} from '../components';
import { useAuth } from '../auth/auth-context';
import './Users.scss';

const EMPTY = { email: '', fullName: '', role: 'LECTOR' as Role, password: '' };

export function Users() {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const users = useQuery({ queryKey: ['users'], queryFn: listUsers });

  const create = useMutation({
    mutationFn: createUser,
    onSuccess: () => {
      setForm(EMPTY);
      setError(null);
      setNotice('Cuenta creada.');
      void queryClient.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (e) => setError(e as ApiError),
  });

  const toggleActive = useMutation({
    mutationFn: ({ id, active }: { id: number; active: boolean }) =>
      updateUser(id, { active }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['users'] }),
    onError: (e) => setError(e as ApiError),
  });

  const canWrite = can('users:write');

  return (
    <div className="users">
      <PageHead
        eyebrow="Administracion"
        title="Cuentas"
        lead="Cada persona de la biblioteca tiene una cuenta. El rol decide que puede ver y hacer."
      />

      <div className="users__grid">
        <Card flush>
          <CardHeader
            title="Personas"
            subtitle={
              users.data
                ? `${users.data.length} ${users.data.length === 1 ? 'cuenta' : 'cuentas'}`
                : undefined
            }
          />
          {users.isPending && <LoadingState label="Cargando cuentas" rows={4} />}
          {users.isError && (
            <ErrorState message="No se ha podido cargar la lista." onRetry={() => void users.refetch()} />
          )}
          {users.data?.length === 0 && (
            <EmptyState title="Todavia no hay cuentas" description="Crea la primera en el formulario." />
          )}
          {users.data && users.data.length > 0 && (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">Nombre</th>
                    <th scope="col">Correo</th>
                    <th scope="col">Rol</th>
                    <th scope="col">Estado</th>
                    {canWrite && <th scope="col" className="table__actions">Acciones</th>}
                  </tr>
                </thead>
                <tbody>
                  {users.data.map((user) => (
                    <tr key={user.id}>
                      <th scope="row">{user.fullName}</th>
                      <td className="table__muted">{user.email}</td>
                      <td>
                        <Badge tone={user.role === 'ADMINISTRADOR' ? 'accent' : 'neutral'}>
                          {ROLE_LABEL[user.role]}
                        </Badge>
                      </td>
                      <td>
                        <Badge tone={user.active ? 'success' : 'danger'} dot>
                          {user.active ? 'Activa' : 'Desactivada'}
                        </Badge>
                      </td>
                      {canWrite && (
                        <td className="table__actions">
                          <Button
                            variant="secondary"
                            size="sm"
                            onClick={() =>
                              toggleActive.mutate({ id: user.id, active: !user.active })
                            }
                          >
                            {user.active ? 'Desactivar' : 'Reactivar'}
                          </Button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        {canWrite && (
          <Card>
            <CardHeader title="Nueva cuenta" subtitle="La contrasena minima es de 10 caracteres" />
            <CardBody>
              <form
                className="users__form"
                onSubmit={(e) => {
                  e.preventDefault();
                  create.mutate(form);
                }}
                noValidate
              >
                <Field
                  label="Nombre completo"
                  required
                  value={form.fullName}
                  onChange={(e) => setForm({ ...form, fullName: e.target.value })}
                />
                <Field
                  label="Correo"
                  type="email"
                  required
                  value={form.email}
                  error={error?.fieldError('email')}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                />
                <Field
                  as="select"
                  label="Rol"
                  options={ROLES.map((role) => ({ value: role, label: ROLE_LABEL[role] }))}
                  value={form.role}
                  onChange={(e) => setForm({ ...form, role: e.target.value as Role })}
                />
                <Field
                  label="Contrasena inicial"
                  type="password"
                  required
                  hint="El usuario debera cambiarla al entrar."
                  error={error?.fieldError('password')}
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                />

                {error && !error.fieldError('email') && (
                  <p className="users__error" role="alert">
                    {error.message}
                  </p>
                )}
                {notice && <p className="users__notice">{notice}</p>}

                <Button type="submit" loading={create.isPending}>
                  Crear cuenta
                </Button>
              </form>
            </CardBody>
          </Card>
        )}

        {canWrite && <ReaderImport onDone={() => void users.refetch()} />}
      </div>
    </div>
  );
}

/**
 * The day a library moves to self-hosting: eight hundred members already exist, on a
 * spreadsheet. Every imported account is a reader who chooses their own password on
 * first login, so the sheet never carries one and the desk never distributes one.
 */
export function ReaderImport({ onDone }: { onDone: () => void }) {
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [report, setReport] = useState<ReaderImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const send = useMutation({
    mutationFn: async (file: File) => importReaders(await file.text()),
    onSuccess: (result) => {
      setReport(result);
      setError(null);
      onDone();
    },
    onError: (failure) =>
      setError(failure instanceof ApiError ? failure.message : String(failure)),
  });

  return (
    <Card className="users__import">
      <CardHeader
        title="Cargar la lista de socios de una hoja de calculo"
        subtitle="Columnas: nombre y correo. Cada persona entra con una clave que ella misma elige."
      />
      <CardBody>
        <p className="users__import-hint">
          Las cuentas que ya existen se dejan como estan, con su contrasena. Las que
          llegan nuevas tendran que cambiar una clave provisional en su primer acceso,
          asi que no hay que repartir ninguna.
        </p>
        <label className="users__import-file">
          <input
            ref={fileInput}
            type="file"
            accept="text/csv,.csv"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                setChosen(file.name);
                send.mutate(file);
              }
              // Otherwise choosing the same file again does nothing at all.
              if (fileInput.current) fileInput.current.value = '';
            }}
          />
          <span className="users__import-button">
            {chosen ? 'Cambiar el fichero' : 'Elegir el CSV'}
          </span>
          <span className="users__import-name">{chosen ?? 'Ningun fichero elegido todavia'}</span>
        </label>

        {error && (
          <p className="users__import-error" role="alert">
            {error}
          </p>
        )}

        {report && (
          <div className="users__import-report" role="status">
            <h3 className="users__import-report-title">
              {report.created} {report.created === 1 ? 'cuenta nueva' : 'cuentas nuevas'}
              {report.alreadyThere > 0 ? `, ${report.alreadyThere} ya existian` : ''}
              {report.failed > 0 ? `, ${report.failed} sin guardar` : ''}
            </h3>
            {report.problems.length > 0 && (
              <ul className="users__import-problems">
                {report.problems.map((problem) => (
                  <li key={problem.line}>
                    Linea {problem.line}: {problem.reason}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </CardBody>
    </Card>
  );
}