# Inline chat preview

AndCode can show a loopback dev server as a live, interactive card pinned above the chat
composer, instead of opening the full-screen Guest Browser. The feature is scoped to loopback
(`localhost`, `127.0.0.1`, `::1`) so an arbitrary public page is never embedded in a conversation.

## How a preview appears

Two paths feed the same state:

1. **Agent request** — the in-guest `browser_preview` MCP tool writes
   `.and-code/browser-command.json` with `{"action":"preview","url":"http://127.0.0.1:5173/"}`.
   `BrowserCommandWatcher` consumes it and `AndCodeApp` pins the URL.
2. **Loopback link in a reply** — when an assistant message renders a bare `http://localhost:PORT`
   or `http://127.0.0.1:PORT` link, `MarkdownLite` already turns it into a link; the chat adds a
   small **Preview** chip that pins the same page.

`browser_show` keeps its existing meaning: it opens the full-screen Guest Browser.

## Implementation

- `feature/browser/PreviewUrl.kt` — `isLoopbackPreviewUrl`, the one gate used by every path.
- `feature/browser/GuestWebContent.kt` — the WebView shared by the full-screen browser and the
  inline card. It destroys the view when it leaves composition; agent-driven CDP inspection stays
  debug-only.
- `feature/browser/BrowserOpenCommand.kt` — `BrowserCommand.Open` / `BrowserCommand.Preview`;
  a preview command is rejected unless it is a loopback URL.
- `feature/chat/ChatPreviewCard.kt` — the pinned card and the inline **Preview** chip.
- `ChatHomeScreen` renders the card above the composer; `AndCodeApp` owns the preview URL and
  hands expansion to the existing `guest_browser` route.

## Security and lifecycle

- Loopback-only; no `addJavascriptInterface`, no mixed content, file/content access left off.
- One live WebView at a time; closing the card destroys it.
- In release builds the agent cannot drive the preview over CDP (`WebContentsDebuggingEnabled` is
  debug-only), matching the Guest Browser.
