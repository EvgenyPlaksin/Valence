import encoding from "k6/encoding";
import http from "k6/http";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";
import { Counter, Rate, Trend } from "k6/metrics";
import ws from "k6/ws";

const mailboxes = new SharedArray("mailboxes", () => JSON.parse(open(__ENV.MANIFEST)).mailboxes);
const mode = __ENV.MODE || "soak";
const vuCount = Number(__ENV.VUS || (mode === "smoke" ? 10 : 200));
const rampSeconds = Number(__ENV.RAMP_SECONDS || (mode === "smoke" ? 5 : 30));
const holdSeconds = Number(__ENV.HOLD_SECONDS || (mode === "smoke" ? 20 : 900));
const sendRate = Number(__ENV.SEND_RATE || (mode === "smoke" ? 5 : 20));
const receiverCount = Number(__ENV.RECEIVER_COUNT || (mode === "smoke" ? 2 : 4));
const nodeAUrl = __ENV.NODE_A_URL || "http://server:8081";
const nodeBUrl = __ENV.NODE_B_URL || "http://server-b:8081";

const sendLatency = new Trend("loadtest_send_latency_ms", true);
const deliveryLatency = new Trend("loadtest_delivery_latency_ms", true);
const acceptedMessages = new Counter("loadtest_accepted_messages");
const deliveredMessages = new Counter("loadtest_delivered_messages");
const websocketErrors = new Rate("loadtest_websocket_errors");
const sequenceErrors = new Counter("loadtest_sequence_errors");
const frameErrors = new Counter("loadtest_frame_errors");
const wsClose1001 = new Counter("loadtest_ws_close_1001");
const wsClose4000 = new Counter("loadtest_ws_close_4000");
const wsCloseOther = new Counter("loadtest_ws_close_other");
const quotaPassed = new Counter("loadtest_quota_passed");

function configureScenarios() {
  if (mode === "calibrate") {
    return {
      idle_sockets: {
        executor: "ramping-vus",
        startVUs: 0,
        stages: [
          { duration: `${rampSeconds}s`, target: vuCount },
          { duration: `${holdSeconds}s`, target: vuCount },
          { duration: "5s", target: 0 },
        ],
        exec: "idleSocket",
        gracefulRampDown: "5s",
      },
    };
  }

  if (mode === "fencing" || mode === "fencing-down" || mode === "fencing-up") {
    return {
      old_socket: {
        executor: "per-vu-iterations",
        vus: 1,
        iterations: 1,
        maxDuration: "15s",
        exec: "fencingOldSocket",
      },
      replacement_socket: {
        executor: "per-vu-iterations",
        vus: 1,
        iterations: 1,
        maxDuration: "12s",
        startTime: "1s",
        exec: "fencingReplacementSocket",
      },
      replay_socket: {
        executor: "per-vu-iterations",
        vus: 1,
        iterations: 1,
        maxDuration: "8s",
        startTime: "6s",
        exec: "fencingReplaySocket",
      },
    };
  }

  if (mode === "drain") {
    return {
      drain_test: {
        executor: "per-vu-iterations",
        vus: 1,
        iterations: 1,
        maxDuration: "90s",
        exec: "drainTest",
      },
    };
  }

  if (mode === "redis-down" || mode === "redis-up") {
    return {
      redis_delivery_test: {
        executor: "per-vu-iterations",
        vus: 1,
        iterations: 1,
        maxDuration: "60s",
        exec: "redisDeliveryTest",
      },
    };
  }

  if (mode === "quota") {
    return {
      quota_test: {
        executor: "per-vu-iterations",
        vus: 1,
        iterations: 1,
        maxDuration: "60s",
        exec: "quotaTest",
      },
    };
  }

  const baseScenarios = {
    idle_sockets: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: `${rampSeconds}s`, target: vuCount },
        { duration: `${holdSeconds + 45}s`, target: vuCount },
        { duration: "5s", target: 0 },
      ],
      exec: "idleSocket",
      gracefulRampDown: "5s",
    },
    send_traffic: {
      executor: "constant-arrival-rate",
      rate: sendRate,
      timeUnit: "1s",
      duration: `${holdSeconds}s`,
      startTime: `${rampSeconds}s`,
      preAllocatedVUs: Math.max(10, sendRate),
      maxVUs: Math.max(50, sendRate * 2),
      exec: "sendTraffic",
    },
    metrics_scrape: {
      executor: "constant-arrival-rate",
      rate: 1,
      timeUnit: "20s",
      duration: `${holdSeconds}s`,
      startTime: `${rampSeconds}s`,
      preAllocatedVUs: 1,
      maxVUs: 2,
      exec: "scrapeMetrics",
    },
  };

  return baseScenarios;
}

export const options = {
  scenarios: configureScenarios(),
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function baseUrl() {
  return __ENV.HTTP_URL || "http://haproxy:8080";
}

function websocketUrl(root = baseUrl()) {
  return root.replace(/^http:/, "ws:") + "/v1/socket";
}

export function setup() {
  if (mode !== "fencing" && mode !== "fencing-down" && mode !== "fencing-up") return null;

  const mailbox = mailboxes[0];
  if (!mailbox) return { fencingSeedAccepted: false, fencingMarker: "" };

  const fencingMarker = `fencing|${Date.now()}`;
  const response = http.post(
    `${nodeAUrl}/v1/messages/${mailbox.mailboxId}`,
    JSON.stringify({
      v: 1,
      envelope: encoding.b64encode(fencingMarker, "rawurl"),
      ttlSeconds: 3600,
    }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-Delivery-Key": mailbox.deliveryKey,
      },
    },
  );
  return { fencingSeedAccepted: response.status === 201, fencingMarker };
}

function runSocketSession(mailbox, remainingSeconds, startingSeq) {
  let lastServerSeq = startingSeq;
  let confirmUpTo = 0;
  let confirmedUpTo = startingSeq;
  let closedReasonCode = 0;

  const url = websocketUrl();
  const response = ws.connect(
    url,
    { headers: { Authorization: `Bearer ${mailbox.accessToken}` } },
    (socket) => {
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "sync.resume", v: 1, afterServerSeq: lastServerSeq }));
      });

      socket.on("message", (text) => {
        let frame;
        try {
          frame = JSON.parse(text);
        } catch (_error) {
          frameErrors.add(1);
          return;
        }

        if (frame.type !== "message.deliver") return;

        if (frame.serverSeq <= lastServerSeq) {
          sequenceErrors.add(1);
        }
        lastServerSeq = frame.serverSeq;

        try {
          const content = encoding.b64decode(frame.envelope, "rawurl", "s");
          const marker = content.split("|");
          const sentAt = Number(marker[3]);
          if (Number.isFinite(sentAt)) {
            deliveryLatency.add(Date.now() - sentAt);
          }
          deliveredMessages.add(1);
          confirmUpTo = frame.serverSeq;
        } catch (_error) {
          frameErrors.add(1);
        }
      });

      socket.on("close", (code) => {
        closedReasonCode = code;
        if (code === 1001) {
          wsClose1001.add(1);
        } else if (code === 4000) {
          wsClose4000.add(1);
        } else if (code !== 1000) {
          wsCloseOther.add(1);
        }
      });

      socket.on("error", () => {
        websocketErrors.add(true);
      });

      socket.setInterval(() => {
        if (confirmUpTo > confirmedUpTo) {
          socket.send(JSON.stringify({ type: "delivery.confirm", v: 1, upToServerSeq: confirmUpTo }));
          confirmedUpTo = confirmUpTo;
        }
      }, 250);

      socket.setTimeout(() => {
        socket.close();
      }, remainingSeconds * 1000);
    },
  );

  const upgraded = response && response.status === 101;
  check(response, { "WebSocket upgraded": () => upgraded });
  if (!upgraded) {
    websocketErrors.add(true);
  }

  return { closedReasonCode, lastServerSeq };
}

export function idleSocket() {
  const mailbox = mailboxes[__VU - 1];
  if (!mailbox) {
    websocketErrors.add(true);
    return;
  }

  let remaining = rampSeconds + holdSeconds + (mode === "calibrate" ? 10 : 50);
  let resumeSeq = 0;

  while (remaining > 5) {
    const sessionLifetime = Math.min(remaining, 60 + Math.floor(Math.random() * 60));
    const start = Date.now();
    const result = runSocketSession(mailbox, sessionLifetime, resumeSeq);
    resumeSeq = result.lastServerSeq;

    const elapsed = Math.floor((Date.now() - start) / 1000);
    remaining -= elapsed;

    if (result.closedReasonCode === 1001) {
      const jitterMs = 50 + Math.floor(Math.random() * 150);
      sleep(jitterMs / 1000);
      remaining -= jitterMs / 1000;
    } else {
      break;
    }
  }
}

export function sendTraffic() {
  const mailboxIndex = (__ITER + __VU - 1) % receiverCount;
  const mailbox = mailboxes[mailboxIndex];
  if (!mailbox) {
    return;
  }

  const marker = `loadtest|${__VU}|${__ITER}|${Date.now()}`;
  const envelope = encoding.b64encode(marker, "rawurl");
  const started = Date.now();
  const response = http.post(
    `${baseUrl()}/v1/messages/${mailbox.mailboxId}`,
    JSON.stringify({ v: 1, envelope, ttlSeconds: 3600 }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-Delivery-Key": mailbox.deliveryKey,
      },
      tags: { name: "message_send" },
    },
  );

  sendLatency.add(Date.now() - started);
  const accepted = response.status === 201;
  check(response, { "message accepted": () => accepted });
  if (accepted) {
    acceptedMessages.add(1);
  }
}

export function scrapeMetrics() {
  const token = __ENV.VALENCE_METRICS_TOKEN;
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  const response = http.get(`${baseUrl()}/metrics`, { headers, tags: { name: "metrics_scrape" } });
  check(response, { "metrics HTTP 200": (res) => res.status === 200 });
}

export function fencingOldSocket(data) {
  const mailbox = mailboxes[0];
  if (!mailbox || !data || !data.fencingSeedAccepted) {
    check(null, { "fencing seed message accepted": () => false });
    return;
  }

  let closeCode = 0;
  let received = false;
  let staleConfirmAttempted = false;
  const response = ws.connect(
    websocketUrl(nodeAUrl),
    { headers: { Authorization: `Bearer ${mailbox.accessToken}` } },
    (socket) => {
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "sync.resume", v: 1, afterServerSeq: 0 }));
      });
      socket.on("message", (text) => {
        const frame = JSON.parse(text);
        if (frame.type !== "message.deliver") return;
        if (encoding.b64decode(frame.envelope, "rawurl", "s") !== data.fencingMarker) return;
        received = true;
        socket.setTimeout(() => {
          if (mode === "fencing-down") {
            staleConfirmAttempted = true;
            socket.send(JSON.stringify({ type: "delivery.confirm", v: 1, upToServerSeq: frame.serverSeq }));
          }
        }, 2500);
      });
      socket.on("close", (code) => {
        closeCode = code;
        if (code === 4000) wsClose4000.add(1);
      });
      socket.setTimeout(() => socket.close(), 14000);
    },
  );

  check(response, { "old WebSocket upgraded": (res) => res && res.status === 101 });
  check(null, { "old socket received the seeded message": () => received });
  check(null, { "old socket closed with 4000": () => closeCode === 4000 });
  if (mode === "fencing-down") {
    check(null, { "stale confirmation was attempted": () => staleConfirmAttempted });
  }
}

export function fencingReplacementSocket(data) {
  const mailbox = mailboxes[0];
  if (!mailbox || !data || !data.fencingSeedAccepted) {
    check(null, { "fencing seed message accepted": () => false });
    return;
  }

  let received = false;
  const response = ws.connect(
    websocketUrl(nodeBUrl),
    { headers: { Authorization: `Bearer ${mailbox.accessToken}` } },
    (socket) => {
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "sync.resume", v: 1, afterServerSeq: 0 }));
      });
      socket.on("message", (text) => {
        const frame = JSON.parse(text);
        if (frame.type === "message.deliver" &&
            encoding.b64decode(frame.envelope, "rawurl", "s") === data.fencingMarker) {
          received = true;
        }
      });
      socket.setTimeout(() => socket.close(), 11000);
    },
  );

  check(response, { "replacement WebSocket upgraded": (res) => res && res.status === 101 });
  check(null, { "replacement socket received the seeded message": () => received });
}

export function fencingReplaySocket(data) {
  const mailbox = mailboxes[0];
  if (!mailbox || !data || !data.fencingSeedAccepted) {
    check(null, { "fencing seed message accepted": () => false });
    return;
  }

  let replayed = false;
  const response = ws.connect(
    websocketUrl(nodeBUrl),
    { headers: { Authorization: `Bearer ${mailbox.accessToken}` } },
    (socket) => {
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "sync.resume", v: 1, afterServerSeq: 0 }));
      });
      socket.on("message", (text) => {
        const frame = JSON.parse(text);
        if (frame.type !== "message.deliver" ||
            encoding.b64decode(frame.envelope, "rawurl", "s") !== data.fencingMarker) return;
        replayed = true;
        socket.send(JSON.stringify({ type: "delivery.confirm", v: 1, upToServerSeq: frame.serverSeq }));
        socket.setTimeout(() => socket.close(), 500);
      });
      socket.setTimeout(() => socket.close(), 7000);
    },
  );

  check(response, { "replay WebSocket upgraded": (res) => res && res.status === 101 });
  check(null, { "replacement socket replayed after the stale confirmation": () => replayed });
  if (replayed) {
    deliveredMessages.add(1);
  }
}

export function drainTest() {
  const mailbox = mailboxes[0];
  if (!mailbox) {
    check(null, { "drain mailbox exists": () => false });
    return;
  }

  let closeCode = 0;
  const response = ws.connect(
    websocketUrl(nodeAUrl),
    { headers: { Authorization: `Bearer ${mailbox.accessToken}` } },
    (socket) => {
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "sync.resume", v: 1, afterServerSeq: 0 }));
      });
      socket.on("close", (code) => {
        closeCode = code;
        if (code === 1001) wsClose1001.add(1);
      });
      socket.setTimeout(() => socket.close(), 60000);
    },
  );

  check(response, { "drain WebSocket upgraded": (res) => res && res.status === 101 });
  check(null, { "SIGTERM closed socket with 1001": () => closeCode === 1001 });
}

export function redisDeliveryTest() {
  const mailbox = mailboxes[1];
  if (!mailbox) {
    check(null, { "Redis delivery mailbox exists": () => false });
    return;
  }

  const marker = `${mode}|${Date.now()}`;
  const envelope = encoding.b64encode(marker, "rawurl");
  let delivered = false;
  let deliveryLatencyMs = Number.POSITIVE_INFINITY;
  let sentAt = 0;

  const response = ws.connect(
    websocketUrl(nodeBUrl),
    { headers: { Authorization: `Bearer ${mailbox.accessToken}` } },
    (socket) => {
      socket.on("message", (text) => {
        const frame = JSON.parse(text);
        if (frame.type !== "message.deliver") return;
        const content = encoding.b64decode(frame.envelope, "rawurl", "s");
        if (content !== marker) return;

        delivered = true;
        deliveryLatencyMs = Date.now() - sentAt;
        deliveryLatency.add(deliveryLatencyMs);
        deliveredMessages.add(1);
        socket.send(JSON.stringify({ type: "delivery.confirm", v: 1, upToServerSeq: frame.serverSeq }));
        socket.close();
      });
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "sync.resume", v: 1, afterServerSeq: 0 }));
        socket.setTimeout(() => {
          sentAt = Date.now();
          const postRes = http.post(
            `${nodeAUrl}/v1/messages/${mailbox.mailboxId}`,
            JSON.stringify({ v: 1, envelope, ttlSeconds: 3600 }),
            {
              headers: {
                "Content-Type": "application/json",
                "X-Delivery-Key": mailbox.deliveryKey,
              },
            },
          );
          check(postRes, { "cross-node message accepted": (res) => res.status === 201 });
          if (postRes.status !== 201) {
            websocketErrors.add(true);
            socket.close();
          }
        }, 500);
      });
      socket.on("error", () => websocketErrors.add(true));
      socket.setTimeout(() => socket.close(), 55000);
    },
  );

  check(response, { "Redis delivery WebSocket upgraded": (res) => res && res.status === 101 });
  check(null, { "cross-node message delivered": () => delivered });
  if (mode === "redis-up") {
    check(null, { "Redis-up delivery arrived before the 15-second polling floor": () => deliveryLatencyMs < 15000 });
  } else {
    check(null, { "Redis-down delivery arrived through PostgreSQL polling": () => deliveryLatencyMs >= 14000 });
  }
  if (!delivered) websocketErrors.add(true);
}

export function quotaTest() {
  const targetMailbox = mailboxes[mailboxes.length - 1];
  const otherMailbox = mailboxes[mailboxes.length - 2];
  if (!targetMailbox || !otherMailbox) {
    return;
  }

  const payload = JSON.stringify({
    v: 1,
    envelope: encoding.b64encode("quota-test-payload", "rawurl"),
    ttlSeconds: 3600,
  });

  const res = http.post(
    `${baseUrl()}/v1/messages/${targetMailbox.mailboxId}`,
    payload,
    {
      headers: {
        "Content-Type": "application/json",
        "X-Delivery-Key": targetMailbox.deliveryKey,
      },
    },
  );

  const quota429Received = res.status === 429;
  const retryAfterHeaderPresent = Boolean(res.headers["Retry-After"] || res.headers["retry-after"]);

  const unrelatedRes = http.post(
    `${baseUrl()}/v1/messages/${otherMailbox.mailboxId}`,
    payload,
    {
      headers: {
        "Content-Type": "application/json",
        "X-Delivery-Key": otherMailbox.deliveryKey,
      },
    },
  );

  const unrelated201Received = unrelatedRes.status === 201;

  const success = quota429Received && retryAfterHeaderPresent && unrelated201Received;
  check(null, {
    "1001st message received 429": () => quota429Received,
    "429 includes Retry-After header": () => retryAfterHeaderPresent,
    "unrelated mailbox message accepted": () => unrelated201Received,
  });

  if (success) {
    quotaPassed.add(1);
  }
}
