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

