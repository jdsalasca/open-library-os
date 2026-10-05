import type { ReactNode } from 'react';

import { Icon } from './Icon';
import './DataTable.scss';

export type Column<T> = {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  /** Right-aligned numeric or action column. */
  align?: 'end';
  /** Hidden below the medium breakpoint to keep phone rows readable. */
  hideOnMobile?: boolean;
  width?: string;
};

type DataTableProps<T> = {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  onRowClick?: (row: T) => void;
  empty?: ReactNode;
};

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  onRowClick,
  empty,
}: DataTableProps<T>) {
  return (
    <div className="dt__wrap">
      <table className="dt">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                style={column.width ? { width: column.width } : undefined}
                data-align={column.align}
                data-hide-mobile={column.hideOnMobile || undefined}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              data-clickable={onRowClick ? 'true' : undefined}
              tabIndex={onRowClick ? 0 : undefined}
              onKeyDown={
                onRowClick
                  ? (event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onRowClick(row);
                      }
                    }
                  : undefined
              }
            >
              {columns.map((column) => (
                <td key={column.key} data-align={column.align} data-hide-mobile={column.hideOnMobile || undefined}>
                  {column.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && empty}
    </div>
  );
}

type PaginationProps = {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (page: number) => void;
  label?: string;
};

export function Pagination({ page, totalPages, totalElements, onPage, label }: PaginationProps) {
  if (totalElements === 0) return null;

  const from = page * 20 + 1;
  const to = Math.min((page + 1) * 20, totalElements);

  return (
    <nav className="pager" aria-label="Paginacion">
      <p className="pager__summary">
        {label ?? `${from}-${to} de ${totalElements}`}
      </p>
      <div className="pager__controls">
        <button
          type="button"
          className="pager__button"
          onClick={() => onPage(page - 1)}
          disabled={page <= 0}
          aria-label="Pagina anterior"
        >
          <Icon name="arrowRight" size={16} className="pager__back" />
        </button>
        <span className="pager__page">
          Pagina {page + 1} de {totalPages}
        </span>
        <button
          type="button"
          className="pager__button"
          onClick={() => onPage(page + 1)}
          disabled={page + 1 >= totalPages}
          aria-label="Pagina siguiente"
        >
          <Icon name="arrowRight" size={16} />
        </button>
      </div>
    </nav>
  );
}
