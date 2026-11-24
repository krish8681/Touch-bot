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

## How to use

1. Install `NiftyRegimeBot-v1.0-release.apk`.
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
