# BLEEdge bots

Turn a macOS BLEEdge node into a bot driven by a [Bun](https://bun.sh) JS/TS script.
The Go node handles BLE + the mesh + chat encryption; your script just reacts to
messages and decides what to send back.

```
                 newline-delimited JSON (stdin/stdout)
  ┌───────────────┐   events  ───────────►   ┌──────────────┐
  │ bleedge-macos │                           │  bun <script> │
  │   (Go node)   │   ◄───────  commands      │  (your bot)   │
  └───────────────┘                           └──────────────┘
```

## Run

```sh
# build the node (macOS)
make build-macos        # or: go build -o bin/bleedge-macos ./cmd/bleedge-macos

# run it as a bot
./bin/bleedge-macos --bot bots/time-bot.ts
# custom bun path: --bun /opt/homebrew/bin/bun
```

The node runs headless and forwards every chat message to the script. Try it from
the phone chat app: DM the mac node and send `!time`, or post `!time` on the Channel.

## Writing a bot

```ts
import { run } from "./sdk";

run({
  onReady: (self, api) => api.log(`ready as ${self.name}`),
  onMessage: (m, api) => {
    if (m.text === "ping") api.reply(m, "pong");
  },
});
```

`api` methods:

| method | effect |
| --- | --- |
| `api.reply(m, text)` | reply in kind — broadcast if `m.channel`, else an encrypted DM to the sender |
| `api.dm(to, text)` | encrypted DM to a node id (only nodes that have DMed us — we need their key) |
| `api.broadcast(text)` | post on the public channel (plaintext) |
| `api.stats()` | `Promise<{peers, neighbors, topology, node, name}>` of live mesh state |
| `api.log(text)` | diagnostic to the node's stderr (never the chat) |

A `Message` is `{ from, name, text, channel, ts }`. **Direct messages are
end-to-end encrypted** by the Go node; your script only ever sees plaintext.

> stdout is the protocol channel. Only the SDK may write there — use `api.log`
> or `console.error` for your own logging.

## Examples

- [`echo-bot.ts`](echo-bot.ts) — echoes every message back.
- [`time-bot.ts`](time-bot.ts) — replies to `!time` with the current time.
- [`stats-bot.ts`](stats-bot.ts) — replies to `!stats` with live mesh stats.
