// Minimal BLEEdge bot SDK for the Bun runtime.
//
// The Go node (bleedge-macos --bot <script>) launches this script with `bun run`
// and talks to it over newline-delimited JSON on stdin/stdout. This module hides
// that wire protocol: you register handlers and call helpers, it does the framing.
//
// IMPORTANT: stdout is the protocol channel — only JSON the SDK writes may go
// there. Use api.log(...) or console.error(...) for diagnostics (stderr).

export interface Message {
  /** Sender node id (16-hex). */
  from: string;
  /** Sender's advertised description (may be empty). */
  name: string;
  text: string;
  /** true = received on the public broadcast channel; false = a direct (encrypted) message. */
  channel: boolean;
  /** Unix epoch milliseconds. */
  ts: number;
}

export interface Self {
  node: string;
  name: string;
}

export interface Stats {
  peers: number;
  neighbors: number;
  topology: number;
  node: string;
  name: string;
}

export interface Api {
  /** Reply in kind: broadcast if the message came from the channel, else a direct DM. */
  reply(m: Message, text: string): void;
  /** Send an encrypted direct message to a node (only works for nodes that have DMed us). */
  dm(to: string, text: string): void;
  /** Post a plaintext message on the public channel. */
  broadcast(text: string): void;
  /** Diagnostic log (goes to the node's stderr, never the chat). */
  log(text: string): void;
  /** Ask the node for live mesh statistics. */
  stats(): Promise<Stats>;
}

export interface Handlers {
  onReady?: (self: Self, api: Api) => void;
  onMessage?: (m: Message, api: Api) => void | Promise<void>;
}

export function run(handlers: Handlers): void {
  const statsWaiters: ((s: Stats) => void)[] = [];

  function send(obj: unknown): void {
    process.stdout.write(JSON.stringify(obj) + "\n");
  }

  const api: Api = {
    reply(m, text) {
      if (m.channel) api.broadcast(text);
      else api.dm(m.from, text);
    },
    dm(to, text) {
      send({ type: "reply", to, text });
    },
    broadcast(text) {
      send({ type: "broadcast", text });
    },
    log(text) {
      send({ type: "log", text });
    },
    stats() {
      return new Promise<Stats>((resolve) => {
        statsWaiters.push(resolve);
        send({ type: "query", what: "stats" });
      });
    },
  };

  const decoder = new TextDecoder();
  let buf = "";
  process.stdin.on("data", (chunk: Buffer) => {
    buf += decoder.decode(chunk);
    let nl: number;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let ev: any;
      try {
        ev = JSON.parse(line);
      } catch {
        continue;
      }
      switch (ev.type) {
        case "ready":
          handlers.onReady?.(ev.self as Self, api);
          break;
        case "message":
          handlers.onMessage?.(ev as Message, api);
          break;
        case "stats":
          statsWaiters.shift()?.(ev as Stats);
          break;
      }
    }
  });
  process.stdin.resume();
}
