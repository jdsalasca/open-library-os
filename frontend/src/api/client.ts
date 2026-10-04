/**
 * Single HTTP entry point for the whole app.
 *
 * Same-origin behind nginx, so session cookies and the CSRF header are all we
 * need. Every backend error is an RFC 9457 ProblemDetail, which we surface as ApiError.
 */

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

export type FieldError = { field: string; message: string };

export type Problem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  fieldErrors?: FieldError[];
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly fieldErrors: FieldError[];

  constructor(status: number, problem: Problem) {
    super(problem.detail || problem.title || `Error ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem.code;
    this.fieldErrors = problem.fieldErrors ?? [];
  }

  /** Field-level message for a form input, if the backend reported one. */
  fieldError(field: string): string | undefined {
    return this.fieldErrors.find((f) => f.field === field)?.message;
  }
}

function readCookie(name: string): string | undefined {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : undefined;
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
};

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal } = options;

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const csrf = readCookie(CSRF_COOKIE);
  if (csrf && method !== 'GET') headers[CSRF_HEADER] = csrf;

  const response = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const payload: unknown = text ? safeParse(text) : null;

  if (!response.ok) {
    throw new ApiError(response.status, (payload ?? {}) as Problem);
  }

  return payload as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'GET', signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};