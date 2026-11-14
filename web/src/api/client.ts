// Fetch wrapper for the API under /api (Caddy strips the prefix). The session is an HttpOnly cookie; writes carry
// the CSRF token from /auth/me as X-CSRF-Token, and every call names the current household (X-Household).

export class ApiError extends Error {
  constructor(public status: number, public detail: string, public body?: unknown) {
    super(detail || `HTTP ${status}`);
  }
}

const session = { csrf: "", household: "" };

export function setSession(csrf: string | undefined, household: number | null | undefined) {
  session.csrf = csrf ?? "";
  session.household = household ? String(household) : "";
}

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

type Body = unknown | FormData;

export async function api<T>(method: string, path: string, body?: Body, opts: { signal?: AbortSignal } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (session.household) headers["X-Household"] = session.household;
  if (method !== "GET" && session.csrf) headers["X-CSRF-Token"] = session.csrf;
  let payload: BodyInit | undefined;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const res = await fetch(`/api${path}`, { method, headers, body: payload, credentials: "same-origin", signal: opts.signal });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown = undefined;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = text;
  }
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith("/auth/me")) onUnauthorized();
    throw new ApiError(res.status, errorText(data, res.status), data);
  }
  return data as T;
}

/** Cloudflare (in front of the site) answers some failures itself: an HTML page, or for JSON clients an RFC 9457
 * body whose detail talks about "the origin web server". Neither is a sentence for the household. */
function fromCloudflare(data: unknown): boolean {
  if (typeof data === "string") return /cloudflare|<html/i.test(data);
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;
  return "ray_id" in d || "cloudflare_error" in d || /cloudflare|origin web server/i.test(String(d.detail ?? ""));
}

export function errorText(data: unknown, status: number): string {
  if (fromCloudflare(data)) {
    if (status === 524 || status === 504) return "The server took too long to answer. Try again in a minute.";
    return "The Meal Prep server couldn't be reached just now. Try again in a minute.";
  }
  if (data && typeof data === "object" && "detail" in data) {
    const d = (data as { detail: unknown }).detail;
    if (typeof d === "string") return d;
    if (Array.isArray(d)) return d.map((e) => (e as { msg?: string }).msg ?? "").filter(Boolean).join("; ");
  }
  if (status === 502 || status === 424) return "The server couldn't reach Loblaws or the import source. Try again in a minute.";
  if (status >= 500) return "Something went wrong on the server.";
  return `Request failed (${status})`;
}

export const get = <T>(path: string, signal?: AbortSignal) => api<T>("GET", path, undefined, { signal });
export const post = <T>(path: string, body?: Body) => api<T>("POST", path, body ?? {});
export const patch = <T>(path: string, body: Body) => api<T>("PATCH", path, body);
export const put = <T>(path: string, body: Body) => api<T>("PUT", path, body);
export const del = <T>(path: string) => api<T>("DELETE", path);

/** The browser sign-in: Google, then back to `next`. */
export function signInUrl(next = location.pathname + location.search): string {
  return `/api/auth/login?${new URLSearchParams({ next })}`;
}
