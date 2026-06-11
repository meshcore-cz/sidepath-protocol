// Echo bot: repeats back whatever it receives.
import { run } from "./sdk";

run({
  onReady: (self, api) => api.log(`echo-bot ready as ${self.name || self.node}`),
  onMessage: (m, api) => {

    if (m.text === "ping") {
      return api.reply(m, "pong");
    }

    console.log('xxx');
    //return api.reply(m, `echo: ${m.text}`);
    return Promise.resolve();
  }
});
