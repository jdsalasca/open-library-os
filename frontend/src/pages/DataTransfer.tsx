import type { LibraryDocument, ImportReport } from '../api/admin';
import { ApiError, api } from '../api/client';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  PageHead,
} from '../components';
import { useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import './DataTransfer.scss';

/**
 * The library in one file, and back again. This is the promise of owning your data:
 * a JSON document you can keep, read and restore on another machine without asking
 * anybody for anything.
 */
/** mbedded drops the page header when this sits inside another screen. */
export function LibraryData({ embedded = false }: { embedded?: boolean } = {}) {
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState<LibraryDocument['counts'] | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);

  const download = useMutation({
    mutationFn: async () => {
      const dump = await api.get<LibraryDocument>('/admin/export');
      setSummary(dump.counts);
      // A Blob keeps the browser from mangling the file name or the accents.
      const blob = new Blob([JSON.stringify(dump, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `openlibrary-${dump.exportedAt.slice(0, 10) || 'export'}.json`;
      link.click();
      URL.revokeObjectURL(url);
      return dump;
    },
    onError: (failure) => setError(message(failure)),
  });

  const restore = useMutation({
    mutationFn: async (file: File) => {
      // The document is already JSON text: sending it as an object would encode it
      // twice and the backend would receive a string instead of a library.
      return api.postJson<ImportReport>('/admin/import', await file.text());
    },
    onSuccess: (result) => {
      setReport(result);
      setError(null);
    },
    onError: (failure) => {
      setReport(null);
      setError(message(failure));
    },
  });

  return (
    <div className="transfer">
{!embedded && (
        <PageHead
          eyebrow="Ajustes"
          title="Tus datos"
          lead="Exporta la biblioteca entera a un fichero y restáurala donde quieras. Tus datos son tuyos, también para llevártelos."
        />
      )}

      <div className="transfer__grid">
        <Card>
          <CardHeader
            title="Exportar"
            subtitle="Un JSON con libros, ejemplares, ubicaciones, préstamos, reservas, ajustes y cuentas"
          />
          <CardBody>
            <p className="transfer__hint">
              El fichero incluye las contraseñas cifradas de las cuentas. Guárdalo como
              guardarías una copia de seguridad, no como un fichero para mandar por correo.
            </p>
            <Button loading={download.isPending} onClick={() => download.mutate()}>
              Descargar la biblioteca
            </Button>

            {summary && (
              <ul className="transfer__counts">
                {Object.entries(summary).map(([key, value]) => (
                  <li key={key}>
                    <span>{key}</span>
                    <Badge tone="neutral">{value}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Importar"
            subtitle="Restaura un fichero exportado. Lo que ya exista se actualiza, no se duplica."
          />
          <CardBody>
            <p className="transfer__hint">
              Importar no borra lo que ya hay: actualiza las filas que comparten código y
              añade las que faltan. Haz una copia antes si tienes dudas.
            </p>
            <label className="transfer__file">
              <input
                ref={fileInput}
                type="file"
                accept="application/json,.json"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) {
                    setChosen(file.name);
                    restore.mutate(file);
                  }
                  // Allow choosing the same file twice in a row.
                  if (fileInput.current) fileInput.current.value = '';
                }}
              />
              <span className="transfer__file-button">
                {chosen ? 'Cambiar el fichero' : 'Elegir el fichero'}
              </span>
              <span className="transfer__file-name">
                {chosen ?? 'Ningun fichero elegido todavia'}
              </span>
            </label>
            <p className="transfer__hint">
              {restore.isPending ? 'Importando…' : 'Elige el fichero .json que exportaste.'}
            </p>

            {report && (
              <div className="transfer__report">
                <h3 className="transfer__report-title">Importacion terminada</h3>
                <table className="transfer__table">
                  <thead>
                    <tr>
                      <th scope="col">Tabla</th>
                      <th scope="col">Nuevos</th>
                      <th scope="col">Actualizados</th>
                      <th scope="col">Omitidos</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tableNames(report).map((name) => (
                      <tr key={name.key}>
                        <th scope="row">{name.label}</th>
                        <td>{report.created?.[name.key] ?? 0}</td>
                        <td>{report.updated?.[name.key] ?? 0}</td>
                        <td>{report.skipped?.[name.key] ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardBody>
        </Card>
      </div>

      {error && (
        <p className="transfer__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

const TABLES: Array<[string, string]> = [
  ['books', 'Libros'],
  ['copies', 'Ejemplares'],
  ['locations', 'Ubicaciones'],
  ['authors', 'Autores'],
  ['publishers', 'Editoriales'],
  ['categories', 'Categorias'],
  ['users', 'Cuentas'],
  ['loans', 'Prestamos'],
  ['reservations', 'Reservas'],
  ['appConfig', 'Ajustes'],
];

function tableNames(report: ImportReport): Array<{ key: string; label: string }> {
  const found = new Set([
    ...Object.keys(report.created ?? {}),
    ...Object.keys(report.updated ?? {}),
    ...Object.keys(report.skipped ?? {}),
  ]);
  const known = TABLES.filter(([key]) => found.has(key)).map(([key, label]) => ({
    key,
    label,
  }));
  // Anything the backend reports that this list does not know about still shows up.
  const extra = [...found]
    .filter((key) => !TABLES.some(([known_]) => known_ === key))
    .map((key) => ({ key, label: key }));
  return [...known, ...extra];
}

function message(failure: unknown): string {
  if (failure instanceof ApiError) return failure.message;
  return 'Algo ha ido mal. Revisa que el fichero sea un JSON válido.';
}
