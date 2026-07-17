import { randomBytes, randomUUID } from "node:crypto";

import { WebSocketServer } from "ws";

import { createHandshakeProof } from "../dist/transport/handshake-authentication.js";

const token = process.env["MINECRAFT_AGENT_SERVER_TOKEN"];
if (token === undefined || token.length < 32) {
  throw new Error("MINECRAFT_AGENT_SERVER_TOKEN is required");
}

const server = new WebSocketServer({
  host: "127.0.0.1",
  port: 38127,
  maxPayload: 16 * 1024,
  perMessageDeflate: false,
});

server.on("connection", (socket) => {
  socket.once("message", (data, isBinary) => {
    if (isBinary) {
      socket.close(1003);
      return;
    }
    const request = JSON.parse(data.toString("utf8"));
    const timestamp = new Date().toISOString();
    const nonce = randomBytes(16).toString("base64url");
    const challenge = request.payload.authentication.challenge;
    const fields = {
      serverId: request.serverId,
      type: "runtime.hello",
      timestamp,
      nonce,
      component: "runtime",
      componentVersion: "0.1.0",
      challenge,
    };
    socket.send(
      JSON.stringify({
        protocolVersion: "1.0",
        messageId: randomUUID(),
        requestId: request.requestId,
        serverId: request.serverId,
        type: "runtime.hello",
        timestamp,
        nonce,
        payload: {
          component: "runtime",
          componentVersion: "0.1.0",
          supportedProtocolVersions: ["1.0"],
          selectedProtocolVersion: null,
          authentication: {
            scheme: "hmac-sha256",
            keyId: request.serverId,
            challenge,
            proof: createHandshakeProof(token, fields),
          },
        },
      }),
    );
  });
});

await new Promise((resolve, reject) => {
  server.once("listening", resolve);
  server.once("error", reject);
});
process.stdout.write("PAPER_SMOKE_RUNTIME_READY\n");

function stop() {
  for (const client of server.clients) {
    client.terminate();
  }
  server.close();
}

process.once("SIGINT", stop);
process.once("SIGTERM", stop);
