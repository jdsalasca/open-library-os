import { api } from './client';
import type { Loan } from './loans';

export type MyReservation = {
  id: number;
  bookId: number;
  bookTitle?: string;
  createdAt: string;
  /** 1 = first in line. */
  place: number;
  queueLength: number;
  availableNow: boolean;
};

export type MyLibrary = {
  loans: Loan[];
  history: Loan[];
  reservations: MyReservation[];
};

export function myLibrary() {
  return api.get<MyLibrary>('/loans/mine');
}

/**
 * Renews one of the reader's own loans. The desk rules decide whether it can:
 * overdue, the renewal cap or somebody waiting in the queue all say no, and the
 * answer arrives as a message worth showing.
 */
export function renewMyLoan(loanId: number) {
  return api.post<Loan>(`/loans/${loanId}/renew`);
}

/** A plain reading of the two numbers that matter on a due date. */
export function daysLeft(dueAt: string, now = new Date()): number {
  const due = new Date(dueAt);
  const startOfDay = (date: Date) =>
    Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
  return Math.round((startOfDay(due) - startOfDay(now)) / 86_400_000);
}

export function dueLabel(dueAt: string, now = new Date()): string {
  const days = daysLeft(dueAt, now);
  if (days < 0) return `Vencio hace ${Math.abs(days)} dias`;
  if (days === 0) return 'Vence hoy';
  if (days === 1) return 'Vence manana';
  return `Quedan ${days} dias`;
}

export function dueTone(dueAt: string, now = new Date()): 'danger' | 'warning' | 'success' {
  const days = daysLeft(dueAt, now);
  if (days < 0) return 'danger';
  if (days <= 3) return 'warning';
  return 'success';
}
