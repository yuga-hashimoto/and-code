# Inline chat preview

AndCode can show a web page inside the conversation — either a loopback dev server or a static
site from the workspace — as a live, interactive card. It stays scoped to loopback
(`localhost`, `127.0.0.1`, `::1`) for server previews, so an arbitrary public page is never
embedded in a conversation.

## Three states

| State | What runs | How it looks |
| --- | --- | --- |
| `Active` | One WebView + live reload | Full card above the composer |
| `Collapsed` | Nothing (WebView destroyed, watcher paused) | Floating **Preview** button, top-right |
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
- `feature/browser/BrowserOpenCommand.kt` — `Open`/`Preview` commands and workspace resolution.
- `feature/chat/ChatPreviewCard.kt` — card controls, floating restore button, reopen chip.
- `feature/chat/PreviewTargets.kt` — loopback targets recovered from the transcript.
- `ChatHomeScreen` / `AndCodeApp` — render the states and own the preview target.

## Security and lifecycle

- Loopback-only for URL previews; no `addJavascriptInterface`, no mixed content, file/content
  access left off. Static serving is limited to the app-owned workspace directory.
- One WebView at a time; minimized and cancelled previews destroy it.
- Agent-driven CDP inspection stays debug-only, matching the Guest Browser.
