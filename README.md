# Nifty Regime Bot

An Android app that trades NIFTY options on Dhan by itself.
It runs fully on the phone. No PC or server is needed.

**Paper mode is on by default.** In paper mode no real orders are sent. Live mode is a switch in Settings.

## What it does, in simple words

1. **Reads the trend** from NIFTY bars (5-min for intraday, 60-min for weekly).
   It uses EMA 9 vs EMA 21 and ADX(14). It follows the trend; it does not predict it.
   A new trend must show on 2 bars in a row before the bot believes it.
2. **Reads how costly options are** (IV). It uses India VIX compared with the last year.
   Low = bottom 30%, High = top 40%. If VIX is not available it uses the ATM option's IV.
3. **Picks one hedged strategy** from this grid. There is never a naked sell.

   | Trend    | IV low              | IV normal           | IV high           |
   |----------|---------------------|---------------------|-------------------|
   | UP       | Bull call spread    | Bull call spread    | Bull put spread   |
   | DOWN     | Bear put spread     | Bear put spread     | Bear call spread  |
   | SIDEWAYS | Long call butterfly | Iron condor         | Iron condor       |

4. **Sizes the trade** so the worst case (max loss + charges) fits your "max loss per trade" setting.
5. **Places the legs one by one.** Buy legs go first, so you are never short without a hedge.
   If a later leg fails, the filled legs are closed again.
6. **Exits** at the first of:
   - Target: 50% of max profit (credit), 60% (debit), 40% (butterfly)
   - Stop: 50% of max loss
   - Trend flips against the trade (iron condor: only on a strong trend, ADX ≥ 25)
   - Time: INTRADAY closes at 15:15. WEEKLY closes from 15:15 on the day before expiry.
   - Your EMERGENCY EXIT button
   Exits buy back short legs first.

## Safety rules built in

- New trades only 09:45–14:30. Max 3 trades a day. Stops after 2 losses in a row.
- Daily loss limit (default ₹20,000). 10-minute wait after each exit.
- Never trades the contract that expires today. WEEKLY needs at least 3 days left to expiry.
- Only LIMIT orders.
- Open positions are saved, so a WEEKLY trade survives a phone restart.
- Every minute in live mode the bot checks Dhan's positions and warns if they do not match.

## v2.0 — clean rewrite

Same trading rules as v1.3, rebuilt as small parts. The full strategy is written up in the doc
"Nifty Regime Bot — Strategy and Rules (v2.0)".

Brain (`core/`, plain Java, 48 tests):

| Part | Job |
| --- | --- |
| `Engine` | Joins the parts; one decision per closed bar, exits every second |
| `BarBook` | Bars, Dhan bar sync and repair, trend reading (5/60-min + 15-min check) |
| `IvGauge` | IV level from India VIX percentile (ATM IV fallback) |
| `StrategyGrid` | Trend × IV → one hedged strategy (INTRADAY debit-only) |
| `EntryGate` | Time, limits, cool-down, filters, charges check |
| `StrategyBuilder` / `Sizer` | Strikes and lots |
| `OrderRunner` | Legs one by one, legging guard, unwind, never-give-up exits |
| `ExitRules` | Target, stops, time, expiry, event, gap |
| `Ledger` | Position and trade bookkeeping after charges |
| `Backtester` | Replays history through the same Engine |

App: `BotService` (runs it) · `DhanLink` (all Dhan traffic) · `Alerts` · `StatusBuilder` ·
`MainActivity` + `SettingsScreen` + `Ui` (screens) · `BacktestRunner` · `Reminder`.

## What's new in v1.3 (each can be switched off in Settings → Entry filters)

- **15-min trend check** (INTRADAY): the 15-minute trend must agree with the 5-minute trend.
- **Chop filter**: no trend trade while ADX is falling, or when the day's range is under 0.25% of NIFTY.
- **Open-interest check** (off by default): skip a bullish trade if the put-call ratio fell over 10% since 09:30, and the reverse.
- **Event days**: no entries on RBI policy / Budget days (2026-27 built in; add more in Settings).
  WEEKLY skips trades with an event before expiry and closes at 15:15 the trading day before an event.
- **Cool-down**: 3 minutes after a win, 10 after a loss, none after a trend-flip exit (new trend may trade at once).
- **Legging guard**: if NIFTY moves more than 25 points while legs are being placed, the entry is undone.
- **Gap check** (WEEKLY): exit at the open if NIFTY gaps 0.8% or more against the trade.
- **Self-test on Home**: Dhan login, bars, VIX history, live VIX, option prices, order-book depth, token renew, open interest.
- The Test tab shows which filters were on, and now loads open interest too.

## What's new in v1.2

- **Test tab (backtest).** Runs the bot's own rules over the last 30/60/90 days of NIFTY and option
  prices from Dhan (expired options data), with your settings and charges. Shows trades, win rate,
  net after charges, worst losing streak, drawdown, results per strategy, and why trades were skipped.
  Limits: bar prices with a small bid/ask gap; next-week options only ATM±3 strikes; VIX = previous close.
- **INTRADAY uses debit trades only** (bull call / bear put spreads). Credit spreads and the iron condor
  are WEEKLY only, because time decay inside one day is small. INTRADAY sideways = no trade
  (or a butterfly, if you switch it on in Settings).
- **Charges check.** A trade is skipped if its profit target is less than 3× its charges.
- **Fairer stop.** The stop uses a fair value: a leg with a wide bid/ask gap (over 5%) is valued at mid.
  A hard stop at 80% of max loss still uses the close-now value. Target still uses the close-now value.

## Data handling (v1.1)

- **Bars match Dhan's chart.** After each bar closes the bot fetches Dhan's own copy and uses it.
  Every 10 minutes it also repairs any bar that was wrong or missing (feed gaps).
  If Dhan's copy does not come within a minute and the bot's own bar is incomplete
  (it missed the bar's start), it makes no new entry and no trend exit on that bar.
- **Paper fills use market depth.** A paper order only takes the quantity shown in the 5-level
  order book within its limit, at worse prices as it goes deeper. Big orders can fill in parts.
- **Dhan token.** The bot shows the token's age. Between 20 and 24 hours it renews the token by itself
  (Dhan's RenewToken, for tokens made on Dhan Web). If that fails you get an alert.
  You can paste a new token in Settings while the bot runs; it reconnects without a restart.
- **Morning check.** Around 09:00 on trading days, if a position is saved but the bot is not running,
  you get an alert. The phone is kept awake for the whole session (renewed every 30 minutes).
- **NSE holidays.** All 2026 holidays are built in (24 Nov 2026 is one). WEEKLY exits on the last
  trading day before expiry. Add next year's dates in Settings.
- **India VIX check.** Home shows a warning if VIX history or live VIX ticks are missing.
- Paper mode no longer polls Dhan's positions and funds every minute.

## How to use

1. Install `NiftyRegimeBot-v2.0-release.apk`.
2. Settings → paste Dhan **Client ID** and **access token**. Check the lot size (65) and tick the box.
3. Choose INTRADAY or WEEKLY. Set your max loss per trade. Save.
4. Settings → Battery → set the app to **Unrestricted**.
5. Home → **Start bot**, wait for READY, then **Start trading**.
6. Watch the Decisions tab to see why each trade was or was not taken.

## Live mode

Live mode places **real orders with real money**.
Since 1 April 2026, SEBI rules need API orders to come from a **static IP registered with Dhan**.
Orders from any other IP are refused with error **DH-905**. Phone mobile data does not have a fixed IP,
so live orders from the phone will fail unless you route through a registered static IP.
Run paper mode for a few weeks first and check that the Trades tab is positive after charges.

## Build

GitHub Actions builds the APK on every push (`.github/workflows/build-apk.yml`).
On the `nifty-regime-bot` branch it also publishes a release named `nifty-regime-bot-v<version>`.
Each new version gets a new file name and the version shows in the app name.

- Brain (plain Java, tested): `app/src/main/java/com/krish/niftyregimebot/core/`
- Android parts: `BotService` (runs the bot), `MainActivity` (screen), `DhanApi`, `DhanFeed`, `DhanLiveBroker`
- Tests: `./gradlew testDebugUnitTest`

This is a tool, not advice. Options can lose money quickly.
