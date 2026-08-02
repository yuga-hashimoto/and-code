# AndCode Landing Page Design

## Goal

Refresh the English AndCode landing page so it feels like the Android app rather than a generic AI product page. The landing page should introduce the app icon clearly, make the app's real UI the primary proof point, and remove the emoji-heavy visual language.

## Scope

The change covers the static GitHub Pages site under `pages/`, the repository screenshot gallery in `README.md` and `README.ja.md`, and their presentation assets. No Android application behavior, release flow, or product copy outside the LP/README presentation changes.

## Experience

### Header

- Use the AndCode app icon beside the wordmark.
- Add compact anchor navigation for Features and How it works.
- Keep GitHub and Download APK available as clear actions.
- Use a dark app-surface header with a thin border and an unobtrusive sticky treatment on narrow screens.

### Hero

- Use a two-column desktop composition and a single-column mobile composition.
- Lead with a short English positioning line focused on coding agents on Android.
- Keep the primary action as the latest APK download and the secondary action as GitHub.
- Show the app icon as a branded product tile and use the existing app screenshots as the visual proof point.
- Add one generated abstract atmosphere image behind the screenshot composition. It must be decorative only: deep navy background, blue/teal signal lines and nodes, no text, no logos, and no fake UI. It should remain low-contrast so the real screenshots stay legible.
- Include a small proof row for native Android, on-device or remote runtimes, and MIT licensing.

### Feature section

- Replace every emoji with a consistent inline SVG icon system.
- Use icons that are visually derived from the app surface: terminal prompt, agent spark, folder, git branch, diff, shield, clock, microphone, and network connection.
- Organize the content into a readable six-to-eight card grid instead of a long undifferentiated list. Preserve only claims already supported by the repository's current product copy.
- Cards use the app's dark surface, light border, blue/teal accent strokes, and small status-like labels.

### How it works

- Keep the three-step flow: download, choose a runtime, start coding.
- Present steps as a horizontal rail on desktop and a vertical timeline on mobile.
- Use numbered SVG markers and a thin connector line rather than emoji or decorative symbols.

### Closing CTA and footer

- Add a compact closing download panel that reuses the icon and the primary CTA.
- Keep GitHub, Releases, and Contributing links.
- Keep the independent-project and MIT-license disclaimer.

### README screenshot gallery

- Replace the current four-image strip with a two-row, three-column HTML table inspired by the existing OpenClaw Assistant README style.
- Use the six supplied screenshots and name them by what they show rather than retaining numeric download filenames.
- Each cell includes an accessible alt text and a visible caption: Navigation drawer, Model & runtime picker, Repository chat, Image generation, Schedules, and Scheduled run result.
- Keep the English and Japanese README galleries in the same order; localize only the captions and alt text in `README.ja.md`.
- Do not force all six screenshots into the LP hero. Use the three most product-defining screens there and keep the complete six-screen gallery in the README.

## Visual system

- Background: deep navy/near-black matching the app screenshots, not purple-black.
- Surfaces: layered navy panels with restrained borders.
- Primary accent: the app's light blue (`#6EA8FE` family).
- Secondary accent: the app's teal (`#55C6C1` family).
- Product mark: use the existing `brand/andcode_icon_master_1024.png` as the source asset; do not redraw or generate a replacement logo.
- Type: retain the existing system/Inter-style sans-serif stack, but use tighter display sizing and stronger hierarchy.
- Motion: only subtle hover/focus transitions; no auto-playing animation or scrolling marquee.

## Implementation boundaries

- Keep the LP as a single static `pages/index.html` with embedded CSS and inline SVG markup so GitHub Pages remains dependency-free.
- Add the generated decorative asset under `pages/` and reference it with a relative path.
- Keep existing screenshot files and reuse them rather than fabricating product UI.
- Copy the six supplied screenshots into both `screenshots/` for repository documentation and `pages/` for the GitHub Pages artifact; use descriptive filenames and leave unrelated existing assets intact.
- Update Open Graph/Twitter image metadata to a stable LP asset if the final composition provides one; otherwise retain the existing screenshot metadata.
- All interactive elements must remain keyboard reachable with visible focus states.
- Decorative SVGs and the generated atmosphere image must be hidden from assistive technology; meaningful images need descriptive alt text.

## Verification

- Render the local page at desktop and mobile viewport widths.
- Confirm the AndCode icon is visible in the header, hero, and closing CTA without being cropped or stretched.
- Confirm there are no emoji characters in the feature/step UI.
- Check that every CTA and footer link has the intended destination.
- Check image loading, readable contrast, keyboard focus, and no horizontal overflow.
- Run `git diff --check` and inspect the final diff before handoff.
