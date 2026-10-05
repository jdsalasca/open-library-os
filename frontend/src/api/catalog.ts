import { api } from './client';

export type AuthorRef = { id: number; name: string; role: string; position: number };
export type CategoryRef = { id: number; name: string; slug: string };
export type PublisherRef = { id: number; name: string };

export type BookSummary = {
  id: number;
  title: string;
  subtitle?: string;
  authors: AuthorRef[];
  publisher?: string;
  publicationYear?: number;
  language?: string;
  pages?: number;
  coverUrl?: string;
  isbn13?: string;
};

export type BookDetail = BookSummary & {
  categories: CategoryRef[];
  summary: string;
  edition?: string;
  isbn10?: string;
};

export type AuthorInput = { name: string; role: string };

export type UpsertBook = {
  title: string;
  subtitle?: string;
  isbn?: string;
  publisher?: string;
  publicationYear?: number;
  language?: string;
  pages?: number;
  summary?: string;
  coverUrl?: string;
  edition?: string;
  authors: AuthorInput[];
  categories: string[];
};

export interface Suggestions {
  totalBooks: number;
  recent: BookSummary[];
  results: BookSummary[];
}

/** The shelf count, the newest arrivals and a quick search, in one call. */
export function suggestions(q?: string) {
  return api.get<Suggestions>(`/catalog/suggestions${q ? `?q=${encodeURIComponent(q)}` : ''}`);
}

export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type BookQuery = {
  q?: string;
  categoryId?: number;
  publisherId?: number;
  year?: number;
  language?: string;
  page?: number;
  size?: number;
  /** "title:asc" or "title:desc"; the backend ignores unknown fields. */
  sort?: string;
};

function queryString(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function searchBooks(query: BookQuery, signal?: AbortSignal) {
  return api.get<Page<BookSummary>>(`/catalog/books${queryString(query)}`, signal);
}

export function getBook(id: number) {
  return api.get<BookDetail>(`/catalog/books/${id}`);
}

export function createBook(book: UpsertBook) {
  return api.post<BookDetail>('/catalog/books', book);
}

export function updateBook(id: number, book: UpsertBook) {
  return api.put<BookDetail>(`/catalog/books/${id}`, book);
}

export function deleteBook(id: number) {
  return api.delete<void>(`/catalog/books/${id}`);
}

export function listAuthors(q?: string) {
  return api.get<Array<{ id: number; name: string; sortName: string }>>(
    `/catalog/authors${queryString({ q })}`,
  );
}

export function listPublishers() {
  return api.get<PublisherRef[]>('/catalog/publishers');
}

export function listCategories() {
  return api.get<CategoryRef[]>('/catalog/categories');
}

export function authorNames(book: BookSummary | BookDetail): string {
  return book.authors.map((a) => a.name).join(', ');
}
