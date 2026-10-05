import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { searchReaders, type DeskReader } from '../api/loans';
import { Button } from './Button';
import { Field } from './Field';
import './reader-picker.scss';

/**
 * Finds a reader for the desk.
 *
 * The old box asked for a database id, which meant the librarian had to know a
 * number nobody remembers. Typing part of a name or a card number is what a
 * counter actually needs, and it survives accents being left out.
 */
export function ReaderPicker({
  onSelect,
  selected,
}: {
  /** `null` means "forget the reader", which is what the change button sends. */
  onSelect: (reader: DeskReader | null) => void;
  selected?: DeskReader | null;
}) {
  const [term, setTerm] = useState('');
  // Typing "brun" must not be four requests; wait for the librarian to stop.
  const [debounced, setDebounced] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term.trim()), 250);
    return () => clearTimeout(timer);
  }, [term]);

  const results = useQuery({
    queryKey: ['loans', 'readers', debounced],
    queryFn: () => searchReaders(debounced),
    enabled: debounced.length >= 2,
    staleTime: 30_000,
  });

  if (selected) {
    return (
      <div className="reader-picker reader-picker--done">
        <p className="reader-picker__chosen">
          <strong>{selected.fullName}</strong>
          <span className="reader-picker__email">{selected.email}</span>
        </p>
        <p className="reader-picker__summary">{loanSummary(selected)}</p>
        <Button variant="secondary" onClick={() => onSelect(null)}>
          Cambiar de lector
        </Button>
      </div>
    );
  }

  return (
    <div className="reader-picker">
      <Field
        label="Lector"
        placeholder="Nombre o correo"
        value={term}
        onChange={(event) => setTerm(event.target.value)}
        hint="Con el nombre o el correo; no hace falta la tilde"
      />

      {debounced.length >= 2 && (
        <ul className="reader-picker__results">
          {results.isPending && <li className="reader-picker__note">Buscando…</li>}
          {results.isError && (
            <li className="reader-picker__note reader-picker__note--bad">
              No se pudo buscar. Revisa la conexion.
            </li>
          )}
          {results.data?.length === 0 && (
            <li className="reader-picker__note">Sin resultados</li>
          )}
          {results.data?.map((reader) => (
            <li key={reader.id}>
              <button type="button" className="reader-picker__hit" onClick={() => onSelect(reader)}>
                <span className="reader-picker__name">{reader.fullName}</span>
                <span className="reader-picker__email">{reader.email}</span>
                <span className="reader-picker__summary">{loanSummary(reader)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function loanSummary(reader: DeskReader) {
  const loans = `${reader.activeLoans} ${reader.activeLoans === 1 ? 'prestamo' : 'prestamos'}`;
  return reader.overdue > 0
    ? `${loans} · ${reader.overdue} ${reader.overdue === 1 ? 'vencido' : 'vencidos'}`
    : loans;
}
