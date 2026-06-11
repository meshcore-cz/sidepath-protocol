// Stats bot: reports live mesh statistics when someone sends "!stats".
import { run } from "./sdk";

run({
  onReady: (self, api) => api.log(`stats-bot ready as ${self.name || self.node}`),
  onMessage: async (m, api) => {
    if (m.text.trim().toLowerCase().startsWith("!stats")) {
      const s = await api.stats();
      api.reply(
        m,
        `📡 peers=${s.peers} · neighbors=${s.neighbors} · known nodes=${s.topology}`,
      );
    }
  },
});
