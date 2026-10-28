export type Session = {
  accessToken: string;
  expiresIn: number;
  user: { id: string; email: string; displayName: string };
};

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

let session: Session | null = null;
let rotation: Promise<boolean> | null = null;
let csrf: { headerName: string; token: string } | null = null;
let csrfLoading: Promise<void> | null = null;
let revision = 0;
const base =
  process.env.NEXT_PUBLIC_PLATFORM_API_URL ?? "http://localhost:8080";

export function setSession(value: Session | null) {
  session = value;
  if (!value) csrf = null;
  revision++;
}

export function hasSession() {
  return session !== null;
}

async function authLock<T>(operation: () => Promise<T>): Promise<T> {
  // Refresh cookies are shared by tabs. Serialize rotations and logout across them.
  if (typeof navigator !== "undefined" && navigator.locks?.request) {
    return navigator.locks.request("ontoflow-auth", operation);
  }
  return operation();
}

async function csrfToken() {
  if (!csrf && !csrfLoading) {
    csrfLoading = (async () => {
      const response = await fetch(`${base}/api/v1/auth/csrf`, {
        credentials: "include",
        cache: "no-store",
      });
      if (!response.ok)
        throw new ApiError("보안 토큰을 가져오지 못했습니다.", response.status);
      csrf = await response.json();
    })().finally(() => {
      csrfLoading = null;
    });
  }
  if (csrfLoading) await csrfLoading;
  return csrf!;
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  accessToken?: string,
  csrfRetry = true,
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const method = (init.method ?? "GET").toUpperCase();
  const cookieMutation =
    path.startsWith("/auth/") && !["GET", "HEAD", "OPTIONS"].includes(method);
  if (cookieMutation) {
    const token = await csrfToken();
    headers.set(token.headerName, token.token);
  }
  const response = await fetch(`${base}/api/v1${path}`, {
    ...init,
    headers,
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (
      cookieMutation &&
      csrfRetry &&
      response.status === 403 &&
      body.message === "CSRF validation failed"
    ) {
      csrf = null;
      return request<T>(path, init, accessToken, false);
    }
    const retryAfter = response.headers.get("Retry-After");
    const wait = retryAfter ? ` (${retryAfter}초 후 다시 시도)` : "";
    const trace = body.traceId ? ` (${body.traceId})` : "";
    throw new ApiError(
      `${body.message ?? "요청에 실패했습니다"}${wait}${trace}`,
      response.status,
    );
  }
  if (["/auth/login", "/auth/register", "/auth/logout"].includes(path))
    csrf = null;
  return response.status === 204 ? (undefined as T) : response.json();
}

async function rotate(): Promise<boolean> {
  if (!rotation) {
    const startedAt = revision;
    rotation = authLock(async () => {
      try {
        const restored = await request<Session>("/auth/refresh", {
          method: "POST",
        });
        if (revision === startedAt) session = restored;
        return session !== null;
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          if (revision === startedAt) session = null;
          return false;
        }
        throw error;
      }
    }).finally(() => {
      rotation = null;
    });
  }
  return rotation;
}

export async function ensureSession(): Promise<boolean> {
  return hasSession() || rotate();
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (["/auth/login", "/auth/register"].includes(path)) {
    return authLock(() => request<T>(path, init));
  }
  if (!(await ensureSession())) throw new ApiError("로그인이 필요합니다.", 401);
  const usedToken = session!.accessToken;
  try {
    return await request<T>(path, init, usedToken);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    // Another request may already have refreshed this access token.
    if (session?.accessToken === usedToken && !(await rotate())) {
      throw new ApiError("로그인이 만료되었습니다.", 401);
    }
    if (!session) throw new ApiError("로그인이 필요합니다.", 401);
    return request<T>(path, init, session.accessToken);
  }
}

export async function logout() {
  const accessToken = session?.accessToken;
  revision++;
  await authLock(async () => {
    try {
      await request<void>("/auth/logout", { method: "POST" }, accessToken);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      // An expired access token must not prevent revoking the persistent cookie session.
      await request<void>("/auth/logout", { method: "POST" });
    }
  });
  session = null;
}
