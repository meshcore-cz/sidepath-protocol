# Sidepath Protocol bots

Turn a macOS Sidepath node into a bot driven by a [Bun](https://bun.sh) JS/TS script.
The Go node handles BLE + the mesh + chat encryption; your script just reacts to
messages and decides what to send back.

```
                 newline-delimited JSON (stdin/stdout)
  ┌───────────────┐   events  ───────────►   ┌──────────────┐
  │   sp daemon    │                           │  bun <script> │
  │   (Go node)   │   ◄───────  commands      │  (your bot)   │
  └───────────────┘                           └──────────────┘
```

## Run

```sh
# build the CLI and helper (macOS)
make sp build-macos-helper

# run it as a bot
./sp daemon run --bot bots/time-bot.ts
# custom bun path: --bun /opt/homebrew/bin/bun
# listen on one or more channels (default: Public)
./sp daemon run --bot bots/echo-bot.ts --channels "Public,dev"
```

The node runs headless and forwards every chat message to the script. By default
the bot listens on the **Public** channel; use `--channels` (comma-separated) to
join one or more. Try it from the phone chat app: DM the mac node and send
`!time`, or post `!time` on a channel the bot has joined.

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
| `api.reply(m, text)` | reply in kind — post on the originating channel if `m.channel`, else an encrypted DM to the sender |
| `api.dm(to, text)` | encrypted DM to a node id (only nodes that have DMed us — we need their key) |
| `api.broadcast(text, channel?)` | post on a channel (MeshCore GRP_TXT); omit `channel` for the bot's primary (first joined) channel |
| `api.typing(to)` | send a "typing…" hint to a node before a slow reply (direct messages only) |
| `api.stats()` | `Promise<{peers, neighbors, topology, node, name}>` of live mesh state |
| `api.log(text)` | diagnostic to the node's stderr (never the chat) |

A `Message` is `{ from, name, text, channel, channelName?, sender?, ts }` —
`channelName` and `sender` are set for channel messages. `self` (in `onReady`)
includes `channels: string[]`, the channels the bot joined. **Direct messages are
end-to-end encrypted** by the Go node; your script only ever sees plaintext.

> stdout is the protocol channel. Only the SDK may write there — use `api.log`
> or `console.error` for your own logging.

## Examples

- [`echo-bot.ts`](echo-bot.ts) — echoes every message back.
- [`time-bot.ts`](time-bot.ts) — replies to `!time` with the current time.
- [`stats-bot.ts`](stats-bot.ts) — replies to `!stats` with live mesh stats.
