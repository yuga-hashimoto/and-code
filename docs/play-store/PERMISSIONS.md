# Permission review

The Play variant currently inherits the common manifest, not a completed minimization.

| Permission / component | Purpose / follow-up |
|---|---|
| MANAGE_EXTERNAL_STORAGE | Direct paths to user-selected workspace files for Linux processes. Requires declaration and Play approval; SAF/copied workspace alternative must be evaluated. |
| READ/WRITE_EXTERNAL_STORAGE | Legacy Android access (max SDK 32/29); verify denial path. |
| INTERNET, network/Wi-Fi and multicast | Provider, remote host and discovery; audit precise use and necessity. |
| FOREGROUND_SERVICE, SPECIAL_USE | Local runtime, Codex and schedules; verify visibility, lifetime, declared subtypes and exceptions. |
| RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE | Explicit voice/wake word; while-in-use and boot restrictions apply. Check denial and revocation. |
| CAMERA | QR scanning; check denial. |
| POST_NOTIFICATIONS | Foreground and user notifications; check denied state. |
| SCHEDULE_EXACT_ALARM | User schedule precision; `ScheduleManager` has inexact fallback. |
| RECEIVE_BOOT_COMPLETED, WAKE_LOCK | Restore schedules/runtime and prevent sleep during user-requested work; test policy and lifetime. |

Source: `app/src/main/AndroidManifest.xml`. These descriptions do not imply approval.
