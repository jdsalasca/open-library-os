import { describe, expect, it } from 'vitest';

import { RESERVE_TEXT } from '../api/loans';
import { daysLeft, dueLabel, dueTone } from '../api/myLibrary';

const NOW = new Date('2026-10-05T12:00:00Z');

describe('la fecha de devolucion', () => {
  it('cuenta dias completos, no horas', () => {
    // Vence mañana a las 09:00 y "hoy" es a las 20:00: sigue siendo un día entero.
    expect(daysLeft('2026-10-06T09:00:00Z', NOW)).toBe(1);
    expect(daysLeft('2026-10-05T09:00:00Z', NOW)).toBe(0);
    expect(daysLeft('2026-10-04T23:59:00Z', NOW)).toBe(-1);
  });

  it('se explica en palabras, no en numeros sueltos', () => {
    expect(dueLabel('2026-10-04T10:00:00Z', NOW)).toBe('Vencio hace 1 dias');
    expect(dueLabel('2026-10-05T10:00:00Z', NOW)).toBe('Vence hoy');
    expect(dueLabel('2026-10-06T10:00:00Z', NOW)).toBe('Vence manana');
    expect(dueLabel('2026-10-20T10:00:00Z', NOW)).toBe('Quedan 15 dias');
  });

  it('avisa antes de que sea tarde', () => {
    expect(dueTone('2026-10-02T10:00:00Z', NOW)).toBe('danger');
    expect(dueTone('2026-10-07T10:00:00Z', NOW)).toBe('warning');
    expect(dueTone('2026-10-30T10:00:00Z', NOW)).toBe('success');
  });

  it('el dia del vencimiento todavia no se pone en rojo', () => {
    expect(dueTone('2026-10-05T10:00:00Z', NOW)).not.toBe('danger');
  });
});

describe('el motivo de una reserva rechazada', () => {
  it('tiene texto para cada codigo que devuelve el backend', () => {
    // The book page shows RESERVE_TEXT[code]; a missing key leaves the reader
    // staring at a raw identifier.
    for (const code of ['book_available', 'already_has_the_book', 'already_reserved']) {
      expect(RESERVE_TEXT[code], `falta el texto para ${code}`).toBeTruthy();
    }
  });
});
