import { describe, expect, it } from 'vitest';

import { REFUSAL_TEXT, type Loan } from '../api/loans';

describe('codigos de rechazo del prestamo', () => {
  // The desk screen shows REFUSAL_TEXT[code] instead of a generic error, so a
  // missing key would leave the librarian staring at nothing.
  const codes = [
    'copy_not_available',
    'reader_limit_reached',
    'reader_has_overdue_loans',
    'book_reserved_by_other_reader',
    'renewal_limit_reached',
    'loan_overdue',
    'loan_not_active',
  ];

  it('has human wording for every refusal LoanPolicy can return', () => {
    for (const code of codes) {
      expect(REFUSAL_TEXT[code], `falta el texto para ${code}`).toBeTruthy();
      expect(REFUSAL_TEXT[code]).not.toBe(code);
    }
  });

  it('covers exactly the codes the backend sends', () => {
    expect(Object.keys(REFUSAL_TEXT).sort()).toEqual([...codes].sort());
  });
});

describe('un prestamo en la lista del mostrador', () => {
  const loan: Loan = {
    id: 1,
    copyId: 2,
    copyCode: 'OL-0000000001',
    bookTitle: 'Neuromante',
    readerId: 3,
    readerEmail: 'lector@local',
    borrowedAt: '2026-10-04T10:00:00Z',
    dueAt: '2026-10-25T10:00:00Z',
    renewals: 0,
    overdue: false,
  };

  it('carries what the desk needs without a second request', () => {
    expect(loan.copyCode).toMatch(/^OL-\d{10}$/);
    expect(new Date(loan.dueAt).getTime()).toBeGreaterThan(new Date(loan.borrowedAt).getTime());
    expect(loan.overdue).toBe(false);
  });
});
