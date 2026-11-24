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

1. Install `NiftyRegimeBot-v1.1-release.apk`.
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
