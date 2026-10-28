import { afterEach, expect, test, vi } from "vitest";
import {
  api,
  ensureSession,
  hasSession,
  logout,
  setSession,
  type Session,
} from "./api";

const session: Session = {
  accessToken: "old-access",
  expiresIn: 900,
  user: { id: "user", email: "u@example.com", displayName: "User" },
};
const csrf = () =>
  Response.json({ headerName: "X-XSRF-TOKEN", token: "csrf-token" });
afterEach(() => {
  setSession(null);
  vi.unstubAllGlobals();
});

test("concurrent unauthorized requests share one cookie-based refresh", async () => {
  setSession(session);
  const next = { ...session, accessToken: "new-access" };
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith("/auth/csrf")) return csrf();
    if (url.endsWith("/auth/refresh")) return Response.json(next);
    if (
      new Headers(init.headers).get("Authorization") === "Bearer old-access"
    ) {
      return new Response(null, { status: 401 });
    }
    return Response.json({ ok: true });
  });
  vi.stubGlobal("fetch", fetcher);
  expect(await Promise.all([api("/tenants"), api("/tenants")])).toEqual([
    { ok: true },
    { ok: true },
  ]);
  const rotations = fetcher.mock.calls.filter(([url]) =>
    url.endsWith("/auth/refresh"),
  );
  expect(rotations).toHaveLength(1);
  expect(rotations[0][1].credentials).toBe("include");
  expect(rotations[0][1].body).toBeUndefined();
  expect(new Headers(rotations[0][1].headers).get("X-XSRF-TOKEN")).toBe(
    "csrf-token",
  );
});

test("reload restores the session once from its HttpOnly cookie", async () => {
  setSession(null);
  const fetcher = vi.fn(async (url: string) =>
    url.endsWith("/auth/csrf") ? csrf() : Response.json(session),
  );
  vi.stubGlobal("fetch", fetcher);
  expect(await Promise.all([ensureSession(), ensureSession()])).toEqual([
    true,
    true,
  ]);
  expect(hasSession()).toBe(true);
  expect(
    fetcher.mock.calls.filter(([url]) => url.endsWith("/auth/refresh")),
  ).toHaveLength(1);
});

test("a rejected refresh clears the session and logout uses cookies and CSRF", async () => {
  setSession(session);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      url.endsWith("/auth/csrf")
        ? csrf()
        : Response.json({ message: "Rejected" }, { status: 401 }),
    ),
  );
  await expect(api("/tenants")).rejects.toThrow();
  expect(hasSession()).toBe(false);

  setSession(session);
  const fetcher = vi.fn(async (url: string) =>
    url.endsWith("/auth/csrf") ? csrf() : new Response(null, { status: 204 }),
  );
  vi.stubGlobal("fetch", fetcher);
  await logout();
  expect(hasSession()).toBe(false);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining("/auth/logout"),
    expect.objectContaining({ method: "POST", credentials: "include" }),
  );
});

test("a failed logout keeps local session state and reports the server failure", async () => {
  setSession(session);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      url.endsWith("/auth/csrf")
        ? csrf()
        : Response.json(
            { message: "Authentication unavailable" },
            { status: 503 },
          ),
    ),
  );
  await expect(logout()).rejects.toThrow("Authentication unavailable");
  expect(hasSession()).toBe(true);
});

test("an expired access token does not prevent cookie-based logout", async () => {
  setSession(session);
  const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url.endsWith("/auth/csrf")) return csrf();
    return new Headers(init.headers).has("Authorization")
      ? Response.json({ message: "Expired" }, { status: 401 })
      : new Response(null, { status: 204 });
  });
  vi.stubGlobal("fetch", fetcher);
  await logout();
  expect(hasSession()).toBe(false);
  const calls = fetcher.mock.calls.filter(([url]) =>
    url.endsWith("/auth/logout"),
  );
  expect(calls).toHaveLength(2);
  expect(new Headers(calls[0][1]?.headers).get("Authorization")).toBe(
    "Bearer old-access",
  );
  expect(new Headers(calls[1][1]?.headers).has("Authorization")).toBe(false);
});

test("logout prevents an in-flight restoration from resurrecting the session", async () => {
  setSession(null);
  let finish: (value: Response) => void = () => {};
  const pending = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  const fetcher = vi.fn(async (url: string) => {
    if (url.endsWith("/auth/csrf")) return csrf();
    if (url.endsWith("/auth/refresh")) return pending;
    return new Response(null, { status: 204 });
  });
  vi.stubGlobal("fetch", fetcher);
  const restore = ensureSession();
  await vi.waitFor(() =>
    expect(
      fetcher.mock.calls.some(([url]) => url.endsWith("/auth/refresh")),
    ).toBe(true),
  );
  await logout();
  finish(Response.json(session));
  expect(await restore).toBe(false);
  expect(hasSession()).toBe(false);
});
