import { randomUUID, timingSafeEqual } from "node:crypto";

const systems = {
  alpha: {
    token: () => process.env.ALPHA_MOCK_TOKEN ?? "alpha-local-token",
    customers: [{ id: "A-C001", name: "김민지", email: "minji@alpha.example" }, { id: "A-C002", name: "이도윤", email: "doyun@alpha.example" }],
    employees: [{ id: "A-E01", name: "박서연" }],
    payments: [{ id: "A-P001", customerId: "A-C001", amount: 72000, currency: "KRW", status: "FAILED", failedAt: "2026-10-20T09:00:00Z" }, { id: "A-P002", customerId: "A-C002", amount: 31000, currency: "KRW", status: "PAID", failedAt: null }],
    tasks: [], drafts: [],
  },
  beta: {
    token: () => process.env.BETA_MOCK_TOKEN ?? "beta-local-token",
    customers: [{ id: "B-C101", name: "최하린", email: "harin@beta.example" }, { id: "B-C102", name: "정우진", email: "woojin@beta.example" }],
    employees: [{ id: "B-E11", name: "한지훈" }],
    payments: [{ id: "B-P101", customerId: "B-C101", amount: 54000, currency: "KRW", status: "DECLINED", failedAt: "2026-10-21T11:00:00Z" }, { id: "B-P102", customerId: "B-C102", amount: 19000, currency: "KRW", status: "SETTLED", failedAt: null }],
    tasks: [], drafts: [],
  },
};

function reply(response, status, value) {
  response.writeHead(status);
  response.end(JSON.stringify(value));
}

async function readBody(request) {
  let raw = "";
  for await (const chunk of request) {
    raw += chunk;
    if (raw.length > 16384) throw new Error("payload_too_large");
  }
  return JSON.parse(raw);
}

export async function routeSystem(request, response) {
  const parts = new URL(request.url, "http://localhost").pathname.split("/").filter(Boolean);
  if (parts[0] !== "systems" || !systems[parts[1]]) return false;
  const system = systems[parts[1]];
  const actual = Buffer.from(request.headers.authorization?.replace(/^Bearer /, "") ?? "");
  const expected = Buffer.from(system.token());
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
    reply(response, 401, { error: "invalid_credential" });
    return true;
  }
  if (request.headers["x-simulate-timeout"] === "true") await new Promise(resolve => setTimeout(resolve, 3000));
  try {
    if (parts[2] === "payments" && request.method === "GET") {
      if (parts.length === 3) reply(response, 200, { items: system.payments });
      else {
        const found = system.payments.find(item => item.id === parts[3]);
        reply(response, found ? 200 : 404, found ?? { error: "not_found" });
      }
    } else if (parts[2] === "customers" && request.method === "GET") {
      const found = system.customers.find(item => item.id === parts[3]);
      reply(response, found ? 200 : 404, found ?? { error: "not_found" });
    } else if (parts[2] === "employees" && request.method === "GET") {
      reply(response, 200, { items: system.employees });
    } else if (parts[2] === "tasks" && request.method === "GET") {
      reply(response, 200, { items: system.tasks });
    } else if (parts[2] === "drafts" && request.method === "GET") {
      reply(response, 200, { items: system.drafts });
    } else if (parts[2] === "tasks" && request.method === "POST") {
      const input = await readBody(request);
      if (!system.customers.some(c => c.id === input.customerId) || !system.employees.some(e => e.id === input.assigneeId) || typeof input.summary !== "string" || !input.summary.trim()) reply(response, 400, { error: "invalid_task" });
      else {
        const task = parts[1] === "alpha" ? { id: randomUUID(), whoId: input.customerId, ownerId: input.assigneeId, subject: input.summary } : { id: randomUUID(), customer_ref: input.customerId, agent_ref: input.assigneeId, title: input.summary };
        system.tasks.push(task);
        reply(response, 201, task);
      }
    } else if (parts[2] === "drafts" && request.method === "POST") {
      const input = await readBody(request);
      if (!system.customers.some(c => c.email === input.to) || typeof input.subject !== "string" || typeof input.content !== "string") reply(response, 400, { error: "invalid_draft" });
      else {
        const draft = { id: randomUUID(), ...input };
        system.drafts.push(draft);
        reply(response, 201, draft);
      }
    } else reply(response, 404, { error: "not_found" });
  } catch {
    reply(response, 400, { error: "invalid_request" });
  }
  return true;
}
