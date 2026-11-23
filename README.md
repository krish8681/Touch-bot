# Nifty Dhan Bot v1.2 (paper trading)

A NIFTY option-buying bot that runs completely on your Android phone. It uses live Dhan
prices, decides CE / PE / no trade, picks a strike, sizes the trade from your risk limit,
and manages the exit. **In this version every order is simulated (paper). Nothing is sent to Dhan.**

## How it trades (plain words)

- Looks at NIFTY 1-minute candles. It does **not** predict. It only follows a trend that is already there.
- **CE** when NIFTY is trending up (fast average above slow average, both rising) and breaks the high of the last 5 minutes.
- **PE** for the mirror image going down. Sideways or unclear → **no trade**.
- Skips a signal if price has already run too far from the average (no chasing).
- Picks the contract with delta near 0.55, a tight bid/ask spread, enough open interest, premium ₹40–450.
- Stop-loss is set from NIFTY's recent movement (ATR) × delta. Quantity is whole lots only, so that the
  worst-case loss (stop + slippage + charges) stays within **₹1,500**. If even 1 lot is too risky → no trade.
- Exits: stop-loss → after +1R the stop moves to break-even → then it trails the best price (lets winners run)
  → time exit at 15 minutes (up to 30 if trailing a winner) → everything closed by 3:15 pm.

These rules are a starting point. They are **not tested on past data yet** and are not proven to make money.
Run paper mode for weeks and look at the Trades tab (shows profit **after** charges) before trusting it.

## Safety limits (change in Settings)

| Limit | Default |
|---|---|
| Max loss per trade | ₹1,500 |
| Daily loss limit (counts open loss too) | ₹6,000 |
| Weekly / monthly loss limit | ₹12,000 / ₹24,000 |
| Trades per day | 3 |
| Open positions | 1 |
| Stop after losses in a row | 2 |
| New entries allowed | 9:25 am – 2:45 pm |
| Cool-down after an exit | 5 minutes |

Limits only block **new** entries. Stop-loss and exits always keep working.
If Dhan shows an F&O position the bot didn't open (for example a manual trade), the bot stops new
entries until you tap "I checked my Dhan account".

## First-time setup

1. Install the APK (allow "install unknown apps" for your browser/file manager).
2. Open **Settings**: enter your Dhan **Client ID** and **Access token** (from web.dhan.co → Profile → DhanHQ Trading APIs).
   Tokens expire in about 24 hours — paste a fresh one each day.
3. Check the **NIFTY lot size** (the exchange changes it sometimes) and tick "I checked this lot size is correct".
4. Tap **Battery settings** and set the app to **Unrestricted**, so Android doesn't kill it.
5. Allow notifications when asked.
6. Home → **Start bot**. Wait for READY. Then **Start trading**.

The indicators need about 27 one-minute candles. The bot loads today's earlier candles from Dhan, so if
you start after 9:45 am it can trade at once.

## Screens

- **Home** – status, NIFTY price, open position with live P&L, today's limits, last signal, Emergency exit.
- **Signals** – every minute's decision with the reason (also why it said no trade).
- **Trades** – every closed trade with charges and net result.
- **Log** – everything the bot did, including blocked trades and why.
- **Settings** – login, money limits, lot size, Telegram alerts.

## Why live orders are locked

- Dhan only accepts orders from a **static IP** you register. Your phone's mobile-data IP changes, so live orders
  from the phone would be refused.
- The NSE retail-algo rules (circular NSE/INSP/77559) need Dhan's written OK for running your own algo, plus an undertaking.
- Paper results must look good after charges first.

The live order code exists (`DhanLiveBroker.java`) but the build flag `LIVE_ORDERS_UNLOCKED` is `false`.

## Things to check against Dhan before trusting numbers

- Charges in `core/Charges.java` are estimates (₹20 per order, exchange, STT, stamp, GST).
- Live-feed packet layout in `core/FeedParser.java` follows Dhan's v2 docs; if prices look wrong, the Log tab shows feed errors.
- Option-chain field names (`top_bid_price`, `top_ask_price`, `security_id`, `greeks.delta`) follow Dhan's v2 docs.

## For developers

- `app/src/main/java/com/krish/niftydhanbot/core/` – trading brain in plain Java (no Android), unit-tested.
- `BotService` – foreground service: Dhan REST + WebSocket feed → engine thread → paper broker → journal/alerts.
- `MainActivity` – dark screen; reads a snapshot once a second and sends commands to the service.
- Tests: `./gradlew testDebugUnitTest`. APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/NiftyDhanBot-v1.0-release.apk`.
- GitHub Actions builds the APK on every push (Actions tab → latest run → Artifacts).

## Backtest (tools/backtest/Backtest.java)

Replays NIFTY 1-minute history through the same `Engine` code the app uses. Option prices are
modelled with Black-Scholes (historical option quotes not available), so treat results as a guide.

```
javac -d out $(find app/src/main/java/com/krish/niftydhanbot/core -name "*.java") tools/backtest/Backtest.java
java -cp out Backtest <path-to>/nifty50-historical-data/1min [nolimits] [from=2019-03-01] [to=2023-12-31] [stopAtrMultiple=2.5] ...
```
Any `BotConfig` field can be overridden as `name=value`. Data: github.com/technovusin/nifty50-historical-data.

**Result for the v1.x rules (Mar 2019 – Nov 2026): loses money every year, even before charges.
Do not trade these rules live.**

**ORB (opening-range breakout, `strategy=ORB`) result, Mar 2019 – Nov 2026:** best version on 2019–23
(30-min range, stop = full range, hold ≤ 2 h) was break-even (PF 1.01, 93 trades); on unseen 2024–26 it
made only 3 trades (the stop no longer fits the ₹1,500 risk budget at today's NIFTY level). No edge.
