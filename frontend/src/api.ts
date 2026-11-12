export class ApiError extends Error {
  constructor(public status: number, public errorCode: string, message: string, public fieldErrors: Record<string, string> = {}) {
    super(message);
  }
}
interface Csrf { token: string; headerName: string }
let csrf: Csrf | undefined;
let csrfRequest: Promise<void> | undefined;
export async function refreshCsrf(): Promise<void> {
  csrf = undefined;
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new ApiError(response.status, 'UNAVAILABLE', 'Unable to connect to Backroom. Try again shortly.');
  csrf = await response.json() as Csrf;
}
async function ensureCsrf() {
  if (csrf) return;
  csrfRequest ??= refreshCsrf().finally(() => { csrfRequest = undefined; });
  await csrfRequest;
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  return request<T>(path, options, 'json');
}
/** Downloads a file (such as a label PDF) and saves it with the given name. */
export async function download(path: string, filename: string, data?: unknown): Promise<void> {
  const blob = await request<Blob>(path, { method: data === undefined ? 'GET' : 'POST', body: data === undefined ? undefined : JSON.stringify(data) }, 'blob');
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = filename; link.rel = 'noopener';
  document.body.appendChild(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
async function request<T>(path: string, options: RequestInit, as: 'json' | 'blob'): Promise<T> {
  try {
    const method = options.method ?? 'GET';
    const headers = new Headers(options.headers);
    headers.set('Accept', as === 'blob' ? 'application/pdf, application/problem+json' : 'application/json');
    if (method !== 'GET' && method !== 'HEAD') {
      await ensureCsrf();
      headers.set(csrf!.headerName, csrf!.token);
      if (options.body && !(options.body instanceof URLSearchParams)) headers.set('Content-Type', 'application/json');
    }
    const response = await fetch('/api/v1' + path, { ...options, method, headers, credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as { errorCode?: string; detail?: string; fieldErrors?: Record<string, string> };
      if (response.status === 401 && !path.startsWith('/auth/')) window.dispatchEvent(new Event('backroom:session-expired'));
      if (problem.errorCode === 'CSRF_INVALID') await refreshCsrf().catch(() => {});
      throw new ApiError(response.status, problem.errorCode ?? 'REQUEST_FAILED', problem.detail ?? 'The action could not be completed.', problem.fieldErrors);
    }
    if (response.status === 204) return undefined as T;
    if (as === 'blob') return await response.blob() as T;
    return await response.json() as T;
  } catch (error) {
    if (error instanceof ApiError || (error instanceof DOMException && error.name === 'AbortError')) throw error;
    throw new ApiError(0, 'UNAVAILABLE', 'Unable to connect to Backroom. Check your connection and try again.');
  }
}
export function send<T>(path: string, method: string, data?: unknown): Promise<T> {
  return api<T>(path, { method, body: data === undefined ? undefined : JSON.stringify(data) });
}
export function query(path: string, values: Record<string, string | number | boolean | undefined>): string {
  const parameters = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== '') parameters.set(key, String(value));
  return path + (path.includes('?') ? '&' : '?') + parameters.toString();
}
