import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

import { downloadOverdueCsv } from '../api/loans';
import { ApiError } from '../api/client';

function csvResponse(body: string, status = 200) {
  return new Response(body, {
    status,
    headers: { 'Content-Type': 'text/csv;charset=UTF-8' },
  });
}

describe('descargar el CSV de vencidos', () => {
  // jsdom does not download anything, so the anchor the client builds is the
  // only thing worth observing.
  let anchors: HTMLAnchorElement[];

  beforeEach(() => {
    anchors = [];
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const element = create(tag);
      if (tag === 'a') anchors.push(element as HTMLAnchorElement);
      return element;
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: () => 'blob:csv',
      revokeObjectURL: () => {},
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('asks the API with the session and saves the file', async () => {
    const fetchMock = vi.fn().mockResolvedValue(csvResponse('lector,correo\nAna,a@b.test\n'));
    vi.stubGlobal('fetch', fetchMock);

    await downloadOverdueCsv();

    expect(fetchMock).toHaveBeenCalledWith('/api/loans/overdue.csv', {
      credentials: 'same-origin',
    });
    expect(anchors[0]?.download).toBe('prestamos-vencidos.csv');
  });

  it('reports a refusal instead of saving an empty file', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(csvResponse(JSON.stringify({ detail: 'Prohibido' }), 403)),
    );

    await expect(downloadOverdueCsv()).rejects.toBeInstanceOf(ApiError);
    expect(anchors).toHaveLength(0);
  });
});