import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const base = process.env.PLATFORM_API_URL ?? "http://localhost:8080";
const origin = process.env.WEB_ORIGIN ?? "http://localhost:3000";
const cookies = new Map();

async function request(path, options = {}) {
  const headers = new Headers(options.headers);
  headers.set("Origin", origin);
  if (options.body) headers.set("Content-Type", "application/json");
  if (cookies.size)
    headers.set(
      "Cookie",
      [...cookies].map(([name, value]) => `${name}=${value}`).join("; "),
    );
  const response = await fetch(`${base}/api/v1${path}`, {
    ...options,
    headers,
  });
  for (const header of response.headers.getSetCookie()) {
    const [pair] = header.split(";");
    const split = pair.indexOf("=");
    const name = pair.slice(0, split);
    const value = pair.slice(split + 1);
    if (!value || /max-age=0(?:;|$)/i.test(header)) cookies.delete(name);
    else cookies.set(name, value);
  }
  return response;
}

async function mutate(path, body) {
  const csrfResponse = await request("/auth/csrf");
  assert.equal(csrfResponse.status, 200, "CSRF bootstrap");
  const csrf = await csrfResponse.json();
  return request(path, {
    method: "POST",
    headers: { [csrf.headerName]: csrf.token },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
}

const registered = await mutate("/auth/register", {
  email: `smoke-${randomUUID()}@example.com`,
  password: `Smoke-${randomUUID()}`,
  displayName: "Authentication smoke test",
});
assert.equal(registered.status, 201, "registration");
const first = await registered.json();
assert.ok(
  !("refreshToken" in first),
  "refresh token must never be returned in JSON",
);
assert.ok(
  registered.headers
    .getSetCookie()
    .some((value) => value.includes("refresh=") && value.includes("HttpOnly")),
);

assert.equal(
  (
    await request("/auth/me", {
      headers: { Authorization: `Bearer ${first.accessToken}` },
    })
  ).status,
  200,
);
const refreshed = await mutate("/auth/refresh");
assert.equal(refreshed.status, 200, "cookie-only restoration");
const second = await refreshed.json();
assert.ok(!("refreshToken" in second));
assert.equal((await mutate("/auth/logout")).status, 204);
for (const token of [first.accessToken, second.accessToken]) {
  assert.equal(
    (
      await request("/auth/me", {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).status,
    401,
    "logged-out access token",
  );
}
assert.equal((await mutate("/auth/refresh")).status, 401);

const account = `limit-${randomUUID()}@example.com`;
for (let index = 0; index < 10; index++) {
  assert.equal(
    (
      await mutate("/auth/login", {
        email: account,
        password: "Invalid-password-123",
      })
    ).status,
    401,
  );
}
const limited = await mutate("/auth/login", {
  email: account,
  password: "Invalid-password-123",
});
assert.equal(limited.status, 429, "account rate limit");
assert.ok(Number(limited.headers.get("Retry-After")) > 0, "Retry-After header");
console.log(
  "Auth HTTP smoke passed: CSRF, cookies, restoration, logout revocation, rate limit.",
);
