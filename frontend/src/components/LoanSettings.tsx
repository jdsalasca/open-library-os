import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { loanSettings, saveLoanSettings, type LoanSettings } from '../api/loans';
import { Button, Card, CardBody, CardHeader, Field, Skeleton } from './index';
import './loan-settings.scss';

/**
 * The lending policy, editable.
 *
 * A library that lends three books for twenty days cannot keep lending four for
 * fourteen. These numbers lived in the database with no way to reach them but a
 * shell, which is the opposite of what this project promises.
 */
export function LoanSettings({ onSaved }: { onSaved?: () => void }) {
  const policy = useQuery({ queryKey: ['loan-settings'], queryFn: loanSettings });
  const live = policy.data;

  if (policy.isPending || !live) {
    return <Skeleton height="8rem" />;
  }

  // No key on the values: remounting would wipe both the draft and the
  // confirmation the administrator just got. The "Ahora mismo" line inside the
  // form shows the policy in use, so a change made elsewhere is visible instead
  // of being silently overwritten.
  return <PolicyForm live={live} onSaved={onSaved} />;
}

function PolicyForm({
  live,
  onSaved,
}: {
  live: LoanSettings;
  onSaved?: () => void;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<LoanSettings>(live);

  const save = useMutation({
    mutationFn: saveLoanSettings,
    onSuccess: (saved) => {
      // The response is the new truth. Refetching here would change the key,
      // remount the form and wipe the confirmation before anybody read it.
      queryClient.setQueryData(['loan-settings'], saved);
      void queryClient.invalidateQueries({ queryKey: ['loans', 'dashboard'] });
      onSaved?.();
    },
  });

  const outOfRange = (value: number, max: number) => value < 1 || value > max;
  const invalid = outOfRange(draft.loanDays, 365) || outOfRange(draft.readerLimit, 50);

  return (
    <Card>
      <CardHeader
        title="Reglas de prestamo"
        subtitle="Se aplican al siguiente prestamo, sin reiniciar nada"
      />
      <CardBody>
        <div className="policy__grid">
          <Field
            label="Dias de prestamo"
            type="number"
            min={1}
            max={365}
            value={draft.loanDays}
            onChange={(e) => setDraft({ ...draft, loanDays: Number(e.target.value) })}
            hint="Cuanto tiempo se lleva un libro"
          />
          <Field
            label="Prestamos por lector"
            type="number"
            min={1}
            max={50}
            value={draft.readerLimit}
            onChange={(e) => setDraft({ ...draft, readerLimit: Number(e.target.value) })}
            hint="Cuantos puede tener a la vez"
          />
          <Field
            label="Renovaciones"
            type="number"
            min={0}
            max={10}
            value={draft.maxRenewals}
            onChange={(e) => setDraft({ ...draft, maxRenewals: Number(e.target.value) })}
            hint="0 significa que no se puede renovar"
          />
        </div>

        <div className="policy__actions">
          <Button
            loading={save.isPending}
            disabled={invalid}
            onClick={() => !invalid && save.mutate(draft)}
          >
            Guardar reglas
          </Button>
          <p className="policy__now">
            Ahora mismo: {live.loanDays} dias · max {live.readerLimit} por lector ·{' '}
            {live.maxRenewals} renovaciones
          </p>
        </div>

        {invalid && (
          <p className="policy__error" role="alert">
            Los dias van de 1 a 365 y los prestamos por lector de 1 a 50.
          </p>
        )}
        {save.isError && (
          <p className="policy__error" role="alert">
            {(save.error as Error).message}
          </p>
        )}
        {save.isSuccess && (
          <p className="policy__ok" role="status">
            Guardado. El siguiente prestamo ya sale con estas reglas.
          </p>
        )}
      </CardBody>
    </Card>
  );
}