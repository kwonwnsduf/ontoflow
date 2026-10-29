import { randomUUID } from "node:crypto";
import { routeSystem } from "./systems.js";

export async function handler(request, response) {
  const supplied = request.headers["x-trace-id"] ?? "";
  const traceId = /^[A-Za-z0-9._-]{1,64}$/.test(supplied) ? supplied : randomUUID().replaceAll("-", "");
  response.setHeader("Content-Type", "application/json");
  response.setHeader("X-Trace-Id", traceId);
  response.setHeader("Cache-Control", "no-store");
  if (request.url === "/health") {
    response.writeHead(200);
    response.end(JSON.stringify({ status: "ok", service: "mock-enterprise" }));
    return;
  }
  if (await routeSystem(request, response)) return;
  response.writeHead(404);
  response.end(JSON.stringify({ error: "not_found" }));
}

