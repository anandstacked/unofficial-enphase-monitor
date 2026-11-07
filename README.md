# Unofficial Enphase Monitor

Unofficial Android and Wear OS monitor for compatible Enphase IQ Gateway / Envoy systems and IQ microinverters. Android 10+; Kotlin, Compose, Room. This independent project is not affiliated with, endorsed by, or supported by Enphase Energy. Enphase, Envoy, IQ and Enlighten are their respective owner's marks. No backend, Enphase password collection, or gateway configuration writes.

[**Download the Android APK**](https://github.com/anandstacked/unofficial-enphase-monitor/releases/latest/download/Unofficial-Enphase-Monitor.apk) · [Release notes and checksum](https://github.com/anandstacked/unofficial-enphase-monitor/releases/latest)

## Tested setup and compatibility

Development and user testing have used **one installation**: an **Envoy-S-Metered-EU** gateway (**ENV-S-WM-230**) with **nine IQ8P microinverters** (**IQ8P-72-2-INT**). Gateway firmware was originally reported as **D8.2.4265**. The supplied meter configuration has one active phase, with production and net-consumption metering. These are model details, not a gateway serial or site identifier.

**Other gateway models, firmware versions, inverter models and three-phase installations are unverified.** Similar local APIs do not guarantee that every measurement or authentication flow works across all Enphase gateways. This project does not claim universal gateway support.

The current connection flow requires HTTPS/token access, a compatible `/info` identity response and a readable `/ivp/meters` configuration. Voltage and meter power use `/ivp/meters/readings`; available measurements depend on the gateway's metering hardware and configuration. Grid import/export and calculated home consumption require compatible net-consumption metering. Gateways without the required meter configuration endpoint may fail to connect, even if they expose inverter power elsewhere.

Detailed inverter measurements require compatible fields and permission to read `/ivp/pdm/device_data`. When those are unavailable, the app attempts `/api/v1/production/inverters` for power only. It cannot supply inverter voltage from that fallback. Older gateways/authentication methods and non-metered gateways have not been validated. Firmware changes can require app updates. If reporting compatibility, share the model, firmware and redacted response structure; remove tokens, serials and site/account identifiers.

## App features

Version 1.1 includes a Wear OS companion for the Galaxy Watch6 Classic and a separate Watch Face Format solar face. See [WATCH_SETUP.md](WATCH_SETUP.md) for installation, pairing and device checks. The phone keeps its existing history and encrypted settings when installed as an update.

Phone Settings includes an icon-only **Appearance** selector: system, light and dark. The selection applies immediately and persists between launches. Tap an information icon on a card for its explanation.

Phone Settings includes **Refresh interval**, configurable in whole seconds from 1 to 3600 (default 60). Galaxy Watch direct-access setup is temporarily hidden while its connection flow is investigated. Existing phone-to-watch reading sync remains available. Debug APKs in `dist/` are local build outputs and are not checked into Git.

Version 1.1.10 shows a single gateway-voltage line in History. Touch or drag across it to see a popup anchored to the selected point, listing the report time, voltage, solar production, grid import or export, and calculated home use; unavailable values are shown as a dash. The four-arrow control in the chart header opens a chart-only landscape view; Back closes it and restores the previous orientation. Other measurements remain available from the History picker.

Charts use native [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart/tree/v3.1.0) controls. The time-range picker offers **Auto / 5 min / 15 min / 1 h / 6 h / 24 h / 7 d / 30 d / Custom** windows. The default window is one hour; Auto displays 60 configured refresh intervals (5-second refresh → 5 minutes). Pinch or double-tap to zoom, and drag horizontally to pan. Inspection/zoom pauses the rolling window; **Go live** resumes it. Vertical drags still scroll the screen. Custom ranges accept local dates/times in `YYYY-MM-DD HH:MM` format within the last 30 days.

The other measurement charts show labelled axes, minimum/sample-average/maximum and report counts. The vertical scale follows the readings so small changes remain visible. Each meter channel/inverter has its own series. Longer windows retain original first/last/min/max points in time buckets, with all original reports used for statistics and tap lookup. Gaps over 10 minutes for meters or 20 minutes for inverters remain visible; these describe missing observations, not a diagnosed outage. Empty periods stay empty. CSV export defaults to the visible chart window; Start and End date/time pickers let you choose a separate window. Empty and invalid ranges are reported before saving.

Version 1.1.3 fixes dark-mode inverter cards and adds **Save & test connection** and **Test saved token**. Settings displays the live connection result, authentication errors separately from network/certificate errors, and the saved token's reported role, issue time, lifetime, expiry and remaining time. Replacing an already-saved token immediately updates these details.

## Install and connect

1. Download the APK above on your Android 10+ phone and open the downloaded file. Follow Android's installation prompts. The download is a signed debug build.
2. Connect the phone to your main home Wi-Fi. In Settings, tap **Find my gateway** to discover it and fill in its IP address and serial. If discovery finds more than one gateway, choose yours by its serial. If discovery comes up empty, tap **Help finding the address** for a guide to your router app, then paste the IP and tap **Check this address**. You can leave the serial blank to read it from the gateway. Compare the displayed serial with the Enphase app or gateway label and review the certificate fingerprint before pairing.
3. Sign in to Enlighten **in your own browser**. Obtain an owner token for your own gateway from Enphase's Entrez token page. Paste only the token value in the app and save it. Never commit tokens or passwords to this project.
4. Choose **Save & test connection** after pasting the token. The check starts immediately, bypassing the refresh wait, and shows Connected, Authentication error, or Connection unavailable. **Test saved token** checks existing credentials. Tests have a 45-second total timeout, and normal collection resumes with the configured interval/backoff after completion. The Dashboard defaults to a 60-second delay between checks. If you want low/high voltage alerts, save your own thresholds and allow notifications. Thresholds are initially disabled.

## Token duration

The app cannot extend an Enphase-issued token or automatically renew it from a pasted JWT alone. Enphase's [local-access technical brief](https://enphase.com/en-ca/download/iq-gateway-access-using-local-apis-or-local-ui-token-based-authentication-tech-brief) documents owner tokens valid for one year and installer tokens valid for twelve hours, including the shorter web-UI token behavior for self-installers. Actual issued `iat` / `exp` claims are displayed instead of assuming either duration. Claims are diagnostic hints, not signature verification; the pinned gateway verifies access.

For a short-lived token, use **Get owner token in browser**, sign in with the account that owns this system, and generate a fresh local token for this gateway. If account permissions still yield short-lived access, ask Enphase/your installer to confirm your system-owner association. If HTTP 401/403 occurs before the reported expiry, expiry has not been established: check gateway serial, access permissions and phone/gateway time. Direct watch access cannot be newly provisioned while its Settings control is hidden.

## Recording

- **Monitor while app is closed** (continuous monitoring) **off**: periodic checks run while the activity is visible; they stop when hidden.
- **Monitor while app is closed** **on**: a user-started connected-device foreground service records on home Wi-Fi. Its notification includes a Stop action. Opening the app observes this same collector. This remains relevant with a refresh interval: the interval controls cadence, while the switch controls whether checks continue after leaving the app.
- Force-stop, reboot, Android/OEM battery restrictions, Wi-Fi loss and unavailable/expired gateway access can interrupt recording. Reopen the app after reboot or force-stop to resume. If needed, manually set the app's battery mode to Unrestricted in Android Settings. This is a phone recorder, not a guaranteed always-on home collector.
- The saved refresh interval applies to on-screen and continuous collection. The delay is measured after each completed attempt. Saving a new value reschedules the pending wait from that attempt; an in-flight request continues and cached readings/alerts are retained. Shorter intervals use more battery and Wi-Fi and cannot force the gateway to produce newer report timestamps.
- Failures retry with a 30-second to five-minute backoff, never more frequently than the selected interval. A foreground Refresh requests the next check sooner. Checks do not overlap.
- Only new **gateway report timestamps** create new measurement samples. Polling more often does not make cached data live. History retains 30 days of measurement timestamps and never fabricates backfilled measurements.
- History charts use the selected source/device; gateway-voltage point details join solar and grid readings only at the exact report timestamp. CSV includes every available metric in the selected export range. CSV dates are UTC ISO-8601; screen dates and export controls use the phone's timezone. Credentials are never exported.

## Data interpretation

- Meter roles come from `/ivp/meters`, not guessed IDs. `phaseCount: 1` controls the displayed channel even when `phaseMode` is `three`; unused 3–10 V channels are excluded.
- Positive net-consumption power is import and negative is export. Home power is production plus net-consumption only when source timestamps match. Values retain their original report age.
- Per-inverter AC/DC volts, amps and frequency use the field's milli-unit scale. Temperature is reported in Celsius; lifetime joules convert to Wh before display in kWh.
- Synthetic nine-PCU test records have `active: true`, `modGone: true`, and complete cached readings. They remain visible. The flag is not independently classified as a fault. Empty records do not discard other devices.
- Gateway voltage becomes stale after 10 minutes, and inverter telemetry after 20 minutes. Cached nighttime inverter values stay labeled with report time.
- Alerts use fresh net-consumption voltage, once on entry into a low/high state; fresh recovery within limits re-arms them. Repeated timestamps cannot repeat alerts. Notifications can be denied without disabling recording.
- Source energy counters can disagree. Inverter-reported day/week/lifetime values remain labeled separately from meter cumulative counters. Device-data EIM daily counters are deliberately not used as a solar day total.
- `device_data` access can vary by firmware or owner permissions. Power-only fallback uses `/api/v1/production/inverters`; no voltage is invented when detail fields are missing.

## Security and compatibility

Discovery looks for the Enphase local network service and `envoy.local` on Wi-Fi. It checks private IPv4 candidates using `/info` without a token. When a serial is entered manually, the app checks it against the gateway response. Pairing requires confirmation of the gateway identity and its SHA-256 certificate fingerprint. The app sends the token only after pairing, over HTTPS to the saved private IPv4 address with the pinned certificate. Redirects are disabled. A changed certificate requires re-pairing and a newly saved token.

Tokens are AES-GCM encrypted with a key in Android Keystore, excluded from backups, masked during entry and never logged. Settings screens block screenshots. The phone and watch apps target SDK 35; a future target-SDK upgrade must review Android's local-network permission requirements.

## Build and verification

Run `gradlew.bat testDebugUnitTest assembleDebug lintDebug` on Windows, or `./gradlew testDebugUnitTest assembleDebug lintDebug` elsewhere, using a Java 17+ Gradle runtime and SDK 35. Set your SDK location in your local, ignored `local.properties`.

After building, run `./tools/Package-DebugBuild.ps1` in PowerShell. It verifies the APKs and keeps versioned copies in `dist/`, plus these stable filenames in `dist/latest/`:

- `Unofficial-Enphase-Monitor.apk` — Android phone app.
- `Unofficial-Enphase-Wear.apk` — Wear OS companion.
- `Unofficial-Enphase-Face.apk` — solar watch face.

`dist/latest/build-info.json` records the packaged versions and hashes, with checksums also in `SHA256SUMS.txt`. Run packaging again after each build to update these debug builds. Gradle writes the original phone APK to `app/build/outputs/apk/debug/app-debug.apk`. The `dist/` folder is local and public phone APKs are available through GitHub Releases.

Tests use synthetic device data (no access token or real gateway identifiers). They verify all nine devices, milli-unit/energy conversion, cached timestamps, single-phase channel selection, partially empty device records, matched-time home power and alert transitions.

Physical-phone acceptance checks:

1. Pair the real gateway and verify a current daytime AC/DC voltage and power reading against its JSON endpoints.
2. With continuous mode off, hide the app and confirm no further checks; reopen and confirm one collector resumes.
3. With continuous mode on, lock the screen and verify new gateway timestamps are saved. Swipe away the app, reopen it, and confirm no duplicate samples or duplicate collectors. Test an overnight recording before depending on it.
4. Stop the service from its notification. Check collection stops while hidden.
5. Leave home Wi-Fi, return, and verify the gap and reconnection. Deny notifications and confirm in-app warnings and recording continue.
6. Use test thresholds to check one crossing notification, cached-response suppression, recovery/re-arm and stale-response suppression.
7. Export a CSV and compare its units/timestamps with the source JSON. Test missing detail telemetry and token replacement. Reboot/force-stop must require manual resume.

No live phone, notification, screen-off or overnight result should be assumed from a successful APK build alone.

Back from History, Settings or inverter details returns to the Dashboard, using either Android Back or the app Back button. Back at the Dashboard keeps Android's normal exit behavior. The Dashboard has no connected/checking polling banner or refresh-interval text; a toolbar refresh icon remains available. Polling configuration and explicit connection diagnostics stay in Settings.
