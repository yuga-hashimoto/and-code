# Policy status

| Area | Status | Evidence and next step |
|---|---|---|
| Native downloaded code | Fix required / individual review | Installers and package managers execute downloads; see RESEARCH and ARCHITECTURE. |
| AI reporting | Individual review / partial implementation | The Play UI submits a user-reviewed excerpt as a public GitHub issue; GitHub authentication is required. Accountless reporting and operational moderation are not established. |
| Target 36 | Behavior verification required | Flavor target is 36 and builds with AGP 8.11.1; permission, background and other behavior changes still need end-to-end testing. |
| 16 KB | Insufficient information | 64-bit bundled libraries passed ELF checks and arm64 emulator startup passed; 32-bit loaders and x86_64 execution remain unverified. |
| All-files, specialUse | Individual review | Shared manifest; justify declarations or implement alternatives. |
| Microphone, alarms, boot | Insufficient information | Test permission denials and background restrictions. |
| User data, OAuth, security | Review required | Data flow documented but Play-specific policy and SDK audit incomplete. |
| Billing and IP | Individual review | Verify final commercial flow and licenses/trademarks. |
| Existing distribution regression | Insufficient information | GitHub/F-Droid debug builds and unit suites passed; source-built F-Droid reproducibility and complete release/device regression remain unverified. |

No row constitutes a Google decision. See [research](RESEARCH.md) and [blockers](REMAINING_TASKS.md).
