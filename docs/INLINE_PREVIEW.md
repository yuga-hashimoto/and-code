# Inline chat preview

AndCode can show a web page inside the conversation — either a loopback dev server or a static
site from the workspace — as a live, interactive card. Link previews are always loopback
(`localhost`, `127.0.0.1`, `::1`). An explicit agent `browser_preview` of a public https page is
allowed only after the user turns on **Settings → "Let the agent control the Guest Browser"**, so
a page is never embedded in a conversation automatically.

## Three states

| State | What runs | How it looks |
| --- | --- | --- |
| `Active` | One WebView + live reload | Full card above the composer |
| `Collapsed` | Nothing (WebView destroyed, watcher paused) | Floating **Preview** button above the composer |
| `None` | Nothing | A **Preview** chip above the composer if a target is known |

- **Minimize** tears the WebView down and leaves the floating button (cheap when not watching).
- **Restore** (tap the button) loads the current files fresh, so recent edits are already visible.
- **Expand** opens the full-screen Guest Browser for a dev server, or a full-screen overlay for a
  static site.
- **Refresh** reloads manually.
- **Cancel** removes the preview; the **Preview** chip can restart it from the session's known
  targets without asking the agent again.

## How a preview appears

1. **Agent request** — the in-guest `browser_preview` MCP tool writes
   `.and-code/browser-command.json` with `{"action":"preview","url":"http://127.0.0.1:5173/"}`.
2. **Loopback link in a reply** — a bare `http://localhost:PORT` / `http://127.0.0.1:PORT` link
   renders a small **Preview** chip.
3. **Static site** — when the workspace has a shallow `index.html`, the **Preview** chip serves it
   directly, no dev server or npm needed.

`browser_show` keeps its existing meaning: it opens the full-screen Guest Browser.

## Human handoff and file upload

The card is the same WebView as the full-screen browser, so an agent that needs a step only the
user can do (signing in, approving a prompt, a captcha) asks for it with `browser_handoff`. The app
records `awaiting_user` in `.and-code/browser-status.json` and shows the same **"You have control"**
banner in the card and in the full-screen browser; the agent waits until the user taps **Hand back
to agent** (or **Cancel**, which writes `dismissed`). A handoff never carries a URL, so it cannot
reload the sign-in the user just completed. `browser_type` refuses a password field it can see.

`browser_upload(path)` attaches a file from device storage (`/sdcard`, `/storage`) to the page's
file input with CDP `DOM.setFileInputFiles`, so the Android file picker never opens. It is best
effort: only an input reachable from `document.querySelector` is found, and `click_selector` can
open a site button that creates one lazily.

## Live reload

While a preview is `Active` and the app is foregrounded, the workspace is polled for changes to
web-facing files (html/css/js/…), debounced so one burst of writes causes one reload. Vendored and
generated directories (`node_modules`, `.git`, `build`, `dist`, …) are skipped, and the scan is
capped. Collapsing or backgrounding cancels the watcher, so a hidden preview does no work.
Remote (PC) runtimes are not watched from the phone; their dev server's own hot reload applies.

## Static serving

`StaticAssetInterceptor` serves workspace files to the WebView under the virtual host
`https://andcode.preview/…` through `shouldInterceptRequest`:

- canonical-path check prevents escaping the site root,
- MIME types for html/css/js/images/fonts/wasm,
- `index.html` and single-page-app fallback,
- `Cache-Control: no-store`, so a reload always sees the latest files,
- no server process and no open port.

## Implementation

- `feature/browser/PreviewTarget.kt` — `PreviewTarget` (Url/Static), `PreviewUiState`, entry scan.
- `feature/browser/PreviewUrl.kt` / `PreviewUrls.kt` — loopback gate and URL extraction.
- `feature/browser/StaticAssetInterceptor.kt` — virtual-host static file serving.
- `feature/browser/WorkspaceChangeWatcher.kt` — debounced change fingerprint.
- `feature/browser/GuestWebContent.kt` — shared WebView; accepts the interceptor and reports its
  instance so the watcher can reload it. Destroyed when it leaves composition.
- `feature/browser/BrowserOpenCommand.kt` — `Open`/`Preview`/`Handoff` commands and workspace resolution.
- `feature/chat/ChatPreviewCard.kt` — card controls, floating restore button, reopen chip.
- `feature/chat/PreviewTargets.kt` — loopback targets recovered from the transcript.
- `ChatHomeScreen` / `AndCodeApp` — render the states and own the preview target.

## Security and lifecycle

- Link previews and the default preview policy are loopback-only. A public https preview needs an
  explicit agent request and the agent-control opt-in; no `addJavascriptInterface`, no mixed
  content, file/content access left off. Static serving is limited to the app-owned workspace
  directory.
- One WebView at a time; minimized and cancelled previews destroy it.
- Agent-driven CDP inspection is exposed only after the user opts in with "Let the agent control the
  Guest Browser", and it applies to both the card and the full-screen browser.
