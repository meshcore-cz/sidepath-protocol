// Echo bot: repeats back whatever it receives.
import { run } from "./sdk";

import YahooFinance from "yahoo-finance2";

const yahoo = new YahooFinance();

async function price(symbol: string) {
  const quote = await yahoo.quote(symbol);

  return {
    symbol,
    price: quote.regularMarketPrice,
    currency: quote.currency,
  };
}

run({
  onReady: (self, api) => api.log(`echo-bot ready as ${self.name || self.node}`),
  onMessage: (m, api) => {

    switch (m.text.split(" ")[0].toLowerCase()) {
      case "ping":
        return api.reply(m, "pong");
      case "hello":
        return api.reply(m, "hi there!");
      case "time":
        return api.reply(m, new Date().toISOString());
      case "price":
        const input = m.text.split(/\s+/)[1]?.trim();
        const shortcuts: Record<string, string> = {
          eth: "ETH-USD",
          btc: "BTC-USD",
        };

        if (!input) {
          return api.reply(m, "Please specify a stock symbol.");
        }
        const symbol = shortcuts[input.toLowerCase()] ?? input.toUpperCase();
        return price(symbol).then((p) => api.reply(m, `${p.symbol}: ${p.price} ${p.currency}`));
    }

    return Promise.resolve();
  }
});
