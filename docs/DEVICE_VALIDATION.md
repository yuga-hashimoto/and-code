# Local device validation checklist

The instrumentation sources are compiled in CI. API 26 / API 34 emulator and
physical-device validation is still manual — use this checklist before
publishing a release.

## API 26 physical / emulator (Android 8.0)

- [ ] Install signed release APK
- [ ] Grant `RECORD_AUDIO`, `POST_NOTIFICATIONS` (if applicable)
- [ ] Add remote PC connection over LAN; health version visible
- [ ] Send a chat message; SSE streaming works
- [ ] Approve a dangerous tool from chat and Activity tab
- [ ] Open Workspaces; QR paste, NSD discovery, file explorer
- [ ] Install local runtime; first-run download + sha256 verify
- [ ] Local chat works; provider API key UI round-trip
- [ ] SAF folder import copies under `/workspace`

## API 34 physical / emulator (Android 14)

Same as API 26 plus:

- [ ] POST_NOTIFICATIONS runtime prompt handled gracefully
- [ ] Foreground service notification for local runtime
- [ ] Approval notifications can act from lock screen

## Xiaomi Android 16 physical (HyperOS)

OEM-specific battery / memory kills are the biggest risk for the local
runtime FGS. Capture before tagging a stable release.

- [ ] Background the app while local runtime is running
- [ ] Leave 5 minutes, return, confirm runtime still healthy
- [ ] Leave 30 minutes, confirm FGS notification still visible
- [ ] Force-stop and reboot the device; runtime restarts only on user action

## Battery / memory / disk benchmark

Run `scripts/battery_benchmark.sh` (or follow the manual procedure) with the
device at 100% charge, screen off, local runtime idle for 30 minutes, then a
5-minute chat workload. Record:

| Metric | Value |
|--------|-------|
| Battery drain (idle 30 min, %) | |
| Battery drain (chat 5 min, %) | |
| PRoot process tree RSS (MB) | |
| `files/runtime` disk usage (MB) | |
| `files/runtime/logs` disk usage (MB) | |

Append results to `docs/device-matrix.md` per device / API level.

## Independent Critical/Important review

Before tagging a public stable release, request a second reviewer to check:

- [ ] No secrets in git history (`git log -p | grep -iE 'password|secret|key'`)
- [ ] `THIRD_PARTY_NOTICES.md` lists every runtime dependency and license
- [ ] `network_security_config.xml` denies cleartext for non-LAN hosts
- [ ] Approval notifications cannot auto-accept any dangerous permission
- [ ] Wake-word pack installer rejects non-HTTPS URLs and hash mismatches
- [ ] No PII in `files/runtime/logs/`
- [ ] Local runtime never binds anything other than `127.0.0.1`
