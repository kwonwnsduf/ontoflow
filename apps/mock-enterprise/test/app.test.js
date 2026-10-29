import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";
import { handler } from "../src/app.js";

test("health endpoint reports ready and propagates trace ID", async (context) => {
  const server = createServer(handler).listen(0, "127.0.0.1");
  context.after(() => server.close());
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const response = await fetch(`http://127.0.0.1:${port}/health`, { headers: { "X-Trace-Id": "test-trace" } });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("X-Trace-Id"), "test-trace");
  assert.deepEqual(await response.json(), { status: "ok", service: "mock-enterprise" });
});

test("Alpha and Beta expose separate payment and CRM records", async (context) => {
  const server = createServer(handler).listen(0, "127.0.0.1");
  context.after(() => server.close());
  await new Promise(resolve => server.once("listening", resolve));
  const { port } = server.address();
  const base = `http://127.0.0.1:${port}/systems`;
  const get = async (system, path, token) => fetch(`${base}/${system}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const alpha = await get("alpha", "/payments", "alpha-local-token");
  const beta = await get("beta", "/payments", "beta-local-token");
  assert.equal((await alpha.json()).items[0].status, "FAILED");
  assert.equal((await beta.json()).items[0].status, "DECLINED");
  assert.equal((await get("alpha", "/customers/A-C001", "alpha-local-token")).status, 200);
  assert.equal((await get("beta", "/customers/A-C001", "beta-local-token")).status, 404);
  assert.equal((await get("alpha", "/payments", "beta-local-token")).status, 401);
});

test("CRM follow-up and mail draft validate references", async (context) => {
  const server = createServer(handler).listen(0, "127.0.0.1");
  context.after(() => server.close());
  await new Promise(resolve => server.once("listening", resolve));
  const { port } = server.address();
  const base = `http://127.0.0.1:${port}/systems/beta`;
  const post = (path, payload) => fetch(`${base}${path}`, {
    method: "POST",
    headers: { Authorization: "Bearer beta-local-token", "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const created = await post("/tasks", { customerId: "B-C101", assigneeId: "B-E11", summary: "결제 확인" });
  assert.equal(created.status, 201);
  assert.equal((await created.json()).customer_ref, "B-C101");
  assert.equal((await post("/tasks", { customerId: "A-C001", assigneeId: "B-E11", summary: "잘못된 참조" })).status, 400);
  assert.equal((await post("/drafts", { to: "harin@beta.example", subject: "안내", content: "결제 확인" })).status, 201);
});

