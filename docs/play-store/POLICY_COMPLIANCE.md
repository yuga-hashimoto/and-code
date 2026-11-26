# Policy status

| Area | Status | Evidence and next step |
|---|---|---|
| Native downloaded code | Fix required / individual review | Installers and package managers execute downloads; see RESEARCH and ARCHITECTURE. |
| AI reporting | Fix required | No verified in-app report-to-developer pipeline. |
| Target 36 | Implementation in progress | Flavor target set; build, behavior and supported AGP unverified. |
| 16 KB | Insufficient information | Inspect final artifacts and run device tests. |
| All-files, specialUse | Individual review | Shared manifest; justify declarations or implement alternatives. |
| Microphone, alarms, boot | Insufficient information | Test permission denials and background restrictions. |
| User data, OAuth, security | Review required | Data flow documented but Play-specific policy and SDK audit incomplete. |
| Billing and IP | Individual review | Verify final commercial flow and licenses/trademarks. |
| Existing distribution regression | Insufficient information | Run both flavor suites and release builds. |

No row constitutes a Google decision. See [research](RESEARCH.md) and [blockers](REMAINING_TASKS.md).
