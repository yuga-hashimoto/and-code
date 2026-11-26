# Data safety inventory — draft, not a Console declaration

| Data / path | Destination / questions |
|---|---|
| Prompts, output, files and tool context | Local CLI then user-selected provider; remote OpenCode sends to user-selected remote server. Confirm exactly which provider collects/shares each field. |
| Provider credentials | Android encrypted preferences for app-managed credentials; local OpenCode may receive plaintext `auth.json` inside private runtime. CLIs maintain their own credentials. Audit backups and deletion. |
| GitHub OAuth and repository data | Device flow to GitHub; token held by app settings; verify scope, revocation and redaction. |
| Voice / camera | Voice recognition and QR capture when used; audit system/third-party services and in-app disclosure. |
| Browser pages, screenshots, cookies | Guest browser/agent access and handoff need a full sensitive-data review. |
| Analytics/crashes | Play scaffold uses no-op reporters and does not declare Firebase dependencies; verify merged artifact and transitive SDKs. GitHub behavior differs. |
| Reporting | The experimental Play UI posts a public GitHub issue under the connected account. The user manually enters the excerpt and selects a reason; GitHub username, reason and excerpt are public. No transcript or attachments are automatically sent. Common credentials are rejected, but detection is incomplete. A GitHub account is required; availability to users without one and Play review remain open. |

Use `docs/AUTHENTICATION_AND_DATA_FLOW.md`, `PRIVACY.md` and an artifact/network audit to complete
actual Data safety answers. Update the published privacy policy before any Play submission.
