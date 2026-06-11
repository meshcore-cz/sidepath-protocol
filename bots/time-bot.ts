// Time bot: replies with the current time when someone sends "!time".
import { run } from "./sdk";

run({
  onReady: (self, api) => api.log(`time-bot ready as ${self.name || self.node}`),
  onMessage: (m, api) => {
    if (m.text.trim().toLowerCase().startsWith("!time")) {
      api.reply(m, `🕐 ${new Date().toLocaleString()}`);
    }
  },
});
