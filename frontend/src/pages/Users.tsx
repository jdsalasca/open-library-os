import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '../api/client';
import {
  ROLE_LABEL,
  ROLES,
  createUser,
  listUsers,
  updateUser,
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
      </div>
    </div>
  );
}