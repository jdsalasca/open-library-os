import { api } from './client';
import type { Page } from './catalog';

export type Reservation = {
  id: number;
  bookId: number;
  bookTitle?: string;
  createdAt: string;
  open: boolean;
  /** Who is waiting: the desk has to hand the book to somebody. */
  readerId?: number;
  readerName?: string;
  readerEmail?: string;
  /** Position in the line, counting from the first arrival. */
  place?: number;
};

export type Loan = {
  id: number;
  copyId: number;
  copyCode?: string;
  barcode?: string;
  bookId?: number;
  bookTitle?: string;
  readerId: number;
  readerEmail?: string;
  borrowedAt: string;
  dueAt: string;
  /** Absent while the loan is still open: Jackson omits null fields. */
  returnedAt?: string;
  renewals: number;
  overdue: boolean;
};

export type LoanSettings = {
  loanDays: number;
  readerLimit: number;
  maxRenewals: number;
};

function queryString(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export type LoanState = 'OPEN' | 'CLOSED' | 'ALL';

export function listLoans(
  query: { state?: LoanState; readerId?: number; page?: number; size?: number } = {},
) {
  return api.get<Page<Loan>>(`/loans${queryString(query)}`);
}

/**
 * The overdue list as a spreadsheet, for working down the calls. Goes through
 * the API client so the session and the CSRF rules are the usual ones.
 */
export function downloadOverdueCsv() {
  return api.download('/loans/overdue.csv', 'prestamos-vencidos.csv');
}

export interface UrgentLoan {
  readerName: string;
  readerEmail: string;
  bookTitle: string;
  daysLate: number;
}

export interface Dashboard {
  out: number;
  overdue: number;
  dueToday: number;
  available: number;
  urgent: UrgentLoan[];
}

/** Staff only. The numbers behind the first screen. */
export function loanDashboard() {
  return api.get<Dashboard>('/loans/dashboard');
}

export interface DeskReader {
  id: number;
  email: string;
  fullName: string;
  activeLoans: number;
  overdue: number;
}

/** The desk looks readers up by name, email or card number. Never by id. */
export function searchReaders(q: string) {
  return api.get<DeskReader[]>(`/loans/readers?q=${encodeURIComponent(q)}`);
}

export function borrow(copyId: number, readerId: number) {
  return api.post<Loan>('/loans', { copyId, readerId });
}

export function renewLoan(id: number) {
  return api.post<Loan>(`/loans/${id}/renew`);
}

export function returnLoan(id: number) {
  return api.post<Loan>(`/loans/${id}/return`);
}

export function loanSettings() {
  return api.get<LoanSettings>('/loans/settings');
}

/** Writes the lending policy. Administrator only, and all three numbers together. */
export function saveLoanSettings(settings: LoanSettings) {
  return api.put<LoanSettings>('/loans/settings', settings);
}

export function myReservations() {
  return api.get<Reservation[]>('/loans/reservations');
}

export function reserveBook(bookId: number) {
  return api.post<Reservation>('/loans/reservations', { bookId });
}

export function cancelReservation(id: number) {
  return api.delete<void>(`/loans/reservations/${id}`);
}

export function reservationQueue() {
  return api.get<Reservation[]>('/loans/queue');
}

/**
 * The queue as a spreadsheet: who to call, about which book, in which order.
 * A screen can show "Rayuela" five times and tell the desk nothing.
 */
export function downloadReservationQueueCsv() {
  return api.download('/loans/reservations.csv', 'cola-de-reservas.csv');
}

/** Human wording for the refusal codes LoanPolicy can return. */
export const REFUSAL_TEXT: Record<string, string> = {
  copy_not_available: 'Ese ejemplar no esta disponible',
  reader_limit_reached: 'El lector ya tiene el maximo de prestamos',
  reader_has_overdue_loans: 'El lector tiene prestamos vencidos',
  book_reserved_by_other_reader: 'Hay otro lector esperando este libro',
  renewal_limit_reached: 'Ese prestamo ya no se puede renovar',
  loan_overdue: 'El prestamo esta vencido: hay que devolverlo',
  loan_not_active: 'Ese prestamo ya esta cerrado',
};

/** Why a reservation did not happen, in the reader's own language. */
export const RESERVE_TEXT: Record<string, string> = {
  book_available: 'Ya hay un ejemplar en la estanteria: pidelo en el mostrador',
  already_has_the_book: 'Ya tienes este libro prestado',
  already_reserved: 'Ya estas en la cola de este libro',
};
