import { createServer } from "node:http";
import { handler } from "./app.js";

const port = Number.parseInt(process.env.PORT ?? "8090", 10);
createServer(handler).listen(port, "0.0.0.0", () => {
  console.log(JSON.stringify({ level: "info", service: "mock-enterprise", message: "server_started", port }));
});

