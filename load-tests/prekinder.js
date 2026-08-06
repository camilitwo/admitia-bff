import http from "k6/http";
import ws from "k6/ws";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const restAck = new Trend("prekinder_ack_ms", true);
const realtimeFanout = new Trend("prekinder_fanout_ms", true);
const reconnect = new Trend("prekinder_reconnect_ms", true);

const baseUrl = (__ENV.PREKINDER_BASE_URL || "https://localhost:8081").replace(/\/$/, "");
const token = __ENV.PREKINDER_TOKEN || "";
const processId = __ENV.PREKINDER_PROCESS_ID || "";
const evaluationId = __ENV.PREKINDER_EVALUATION_ID || "";
const headers = { Authorization: `Bearer ${token}`, Accept: "application/json" };

export const options = {
  scenarios: {
    rest_operations: {
      executor: "constant-arrival-rate",
      exec: "restOperations",
      rate: 100,
      timeUnit: "1s",
      duration: __ENV.PREKINDER_LOAD_DURATION || "2m",
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
    realtime_connections: {
      executor: "ramping-vus",
      exec: "realtimeConnections",
      startVUs: 0,
      stages: [
        { duration: "30s", target: 500 },
        { duration: __ENV.PREKINDER_SOCKET_HOLD || "1m", target: 500 },
        { duration: "20s", target: 0 },
      ],
    },
  },
  thresholds: {
    prekinder_ack_ms: ["p(95)<300"],
    prekinder_fanout_ms: ["p(95)<500"],
    prekinder_reconnect_ms: ["p(95)<3000"],
    http_req_failed: ["rate<0.01"],
  },
};

export function restOperations() {
  const started = Date.now();
  const response = http.get(`${baseUrl}/v1/prekinder/processes/${processId}/dashboard`, { headers });
  restAck.add(Date.now() - started);
  check(response, { "REST Prekínder responde 200": (value) => value.status === 200 });
}

export function realtimeConnections() {
  const ticketResponse = http.post(`${baseUrl}/v1/prekinder/realtime/tickets`, null, { headers });
  if (ticketResponse.status !== 200) return;
  const ticket = ticketResponse.json("data.ticket");
  const wsUrl = baseUrl.replace(/^http/, "ws") + "/v1/prekinder/realtime";
  const connectedAt = Date.now();
  const operationId = `${__VU}-${__ITER}-${Date.now()}`;
  const sentAt = Date.now();
  ws.connect(wsUrl, { headers: { Origin: __ENV.PREKINDER_ORIGIN || baseUrl } }, (socket) => {
    socket.on("open", () => {
      reconnect.add(Date.now() - connectedAt);
      socket.send(`CONNECT\naccept-version:1.2\nheart-beat:20000,20000\nX-Prekinder-Ticket:${ticket}\n\n\0`);
    });
    socket.on("message", (message) => {
      if (message.startsWith("CONNECTED")) {
        socket.send("SUBSCRIBE\nid:acks\ndestination:/user/queue/prekinder/acks\n\n\0");
        socket.send("SUBSCRIBE\nid:events\ndestination:/user/queue/prekinder/events\n\n\0");
        if (evaluationId) {
          const body = JSON.stringify({ operationId, type: "WATCH_EVALUATION", evaluationId, clientSequence: 1 });
          socket.send(`SEND\ndestination:/app/prekinder/operations\ncontent-type:application/json\ncontent-length:${body.length}\n\n${body}\0`);
        }
      } else if (message.includes(operationId) || message.includes('"eventId"')) {
        realtimeFanout.add(Date.now() - sentAt);
      }
    });
    socket.setTimeout(() => socket.close(), 15_000);
  });
  sleep(1);
}

