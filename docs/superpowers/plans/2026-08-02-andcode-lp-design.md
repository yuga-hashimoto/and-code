# AndCode Landing Page Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the English GitHub Pages landing page so it presents the real AndCode app icon and UI in an app-aligned visual system without emoji-based feature art.

**Architecture:** Keep the site as one dependency-free static `pages/index.html`. Use the existing app icon and screenshots as product evidence, inline SVG markup for repeatable feature/step icons, and one generated decorative atmosphere image behind the hero composition. CSS variables and responsive grid/flex layouts keep the page maintainable without introducing a build tool.

**Tech Stack:** HTML5, embedded CSS, inline SVG, existing PNG assets, built-in image generation, Python's static HTTP server for local rendering checks.

## Global Constraints

- The LP remains English-only.
- Use `brand/andcode_icon_master_1024.png` as the product mark; do not redraw or generate a replacement logo.
- Preserve the existing screenshots in `pages/` and reuse them instead of fabricating product UI.
- Replace every emoji in feature/step UI with consistent inline SVG icons.
- Keep GitHub Pages dependency-free: no npm package, icon library, or JS framework.
- Use a deep navy/near-black background, light blue `#6EA8FE`, teal `#55C6C1`, and restrained borders.
- Keep meaningful images accessible with alt text and mark decorative SVG/image elements `aria-hidden="true"`.
- Keep all CTAs keyboard reachable with visible focus states and verify no horizontal overflow.

---

### Task 1: Generate the hero atmosphere asset

**Files:**
- Create: `pages/andcode-hero-atmosphere.png`

**Interfaces:**
- Consumes: the approved visual direction and the existing app color language.
- Produces: a low-contrast decorative raster background referenced relatively from `pages/index.html`.

- [ ] **Step 1: Generate one square/landscape decorative image with the built-in image tool**

Use this prompt:

```text
Use case: stylized-concept
Asset type: landing page hero background atmosphere
Primary request: an abstract, premium technical atmosphere for an Android coding-agent app landing page
Scene/backdrop: deep navy near-black field with a subtle layered grid, sparse connected nodes, and thin flowing signal paths
Subject: no literal device, no people, no interface, no logo; only an abstract local-computing / code-network impression
Style/medium: restrained editorial tech illustration, soft depth, crisp vector-like lines with a very subtle grain
Composition/framing: wide landscape composition with generous dark negative space in the center for real screenshots and readable copy
Lighting/mood: calm, focused, precise, quietly energetic
Color palette: #0B1017, #16283B, #6EA8FE, #55C6C1, tiny cream highlights
Text (verbatim): ""
Constraints: decorative only, low contrast, no readable text, no icons, no logos, no fake UI, no watermark
Avoid: neon cyberpunk, purple gradients, emoji, glassmorphism blobs, dense circuitry, bright white background
```

- [ ] **Step 2: Move the selected generated output into the worktree**

Copy the generated file into `pages/andcode-hero-atmosphere.png`; do not leave the only project-bound copy in the default generated-images directory.

- [ ] **Step 3: Inspect the saved asset before wiring it into the page**

Run:

```bash
file pages/andcode-hero-atmosphere.png
sips -g pixelWidth -g pixelHeight pages/andcode-hero-atmosphere.png
```

Expected: a readable PNG with landscape dimensions and no transparent/cropped content that would expose a hard edge behind the hero.

- [ ] **Step 4: Commit the isolated asset**

```bash
git add pages/andcode-hero-atmosphere.png
git commit -m "feat: add AndCode LP hero atmosphere"
```

---

### Task 2: Replace the static LP structure and visual language

**Files:**
- Modify: `pages/index.html`
- Create: `pages/navigation-drawer.jpg`, `pages/model-picker.jpg`, `pages/repository-chat.jpg`, `pages/image-generation.jpg`, `pages/schedules.jpg`, `pages/run-result.jpg`
- Create: `screenshots/navigation-drawer.jpg`, `screenshots/model-picker.jpg`, `screenshots/repository-chat.jpg`, `screenshots/image-generation.jpg`, `screenshots/schedules.jpg`, `screenshots/run-result.jpg`

**Interfaces:**
- Consumes: `brand/andcode_icon_master_1024.png`, the six supplied Downloads screenshots, and `pages/andcode-hero-atmosphere.png`.
- Produces: a complete semantic page with `#features` and `#how-it-works` anchors, working download/GitHub links, inline SVG icon definitions, and responsive presentation styles.

- [ ] **Step 1: Copy and rename the supplied screenshots into the repository**

Use these exact source-to-destination mappings:

```text
/Volumes/MOVESPEED/Downloads/788017_0.jpg -> navigation-drawer.jpg
/Volumes/MOVESPEED/Downloads/788016_0.jpg -> model-picker.jpg
/Volumes/MOVESPEED/Downloads/788014_0.jpg -> repository-chat.jpg
/Volumes/MOVESPEED/Downloads/788013_0.jpg -> image-generation.jpg
/Volumes/MOVESPEED/Downloads/788015_0.jpg -> schedules.jpg
/Volumes/MOVESPEED/Downloads/788021_0.jpg -> run-result.jpg
```

Copy each destination once under `screenshots/` and once under `pages/`. Preserve the original Downloads files.

- [ ] **Step 2: Replace the old document shell and metadata**

Keep the existing title and description intent, but update the document to include a meaningful `og:image` only if the final checked-in asset is a stable public path. Keep the existing screenshot metadata if the hero atmosphere is not suitable as a social preview. Add `meta name="theme-color" content="#0B1017"`.

- [ ] **Step 3: Add the branded header and hero markup**

The header must include the app icon and navigation. The hero must include the following content hierarchy:

```html
<header class="site-header">AndCode mark + wordmark + Features + How it works + GitHub + Download APK</header>
<main>
  <section class="hero" aria-labelledby="hero-title">
    <div class="hero-copy">eyebrow + h1 + supporting copy + Download APK + GitHub</div>
    <div class="hero-visual">atmosphere image + app icon tile + three real screenshots</div>
    <div class="proof-row">Native Android / On-device or remote / MIT licensed</div>
  </section>
</main>
```

Use concise English copy that does not claim unsupported behavior: “Code with your agents. Anywhere.” and “A native Android workspace for OpenCode, Claude Code, and Antigravity.” Keep the APK and GitHub URLs unchanged.

- [ ] **Step 4: Add the feature and workflow content with inline SVG icons**

Use cards for these supported product areas: Local agents, Remote OpenCode, Repository workspace, Git review, Tool approvals, Scheduled tasks, Voice input, and Session management. Each card gets a unique inline SVG icon using `viewBox="0 0 24 24"`, `fill="none"`, and `stroke="currentColor"`; the SVG is decorative and has `aria-hidden="true"`.

Use the existing three steps verbatim in meaning: download the APK, set up an on-device or remote runtime, start coding from the phone. Render the step rail with numbered SVG circles and a connector line.

- [ ] **Step 5: Add the closing CTA and preserve the footer links**

The closing panel repeats the AndCode icon, a short download prompt, and the APK/GitHub actions. Keep links to GitHub, Releases, and Contributing plus the independent-project disclaimer.

- [ ] **Step 6: Run static structural checks before styling review**

Run:

```bash
rg -n '📱|⚡|🤖|🧠|🚀|📂|🌿|📋|🔗|✅|💬|⏰|🎙️|🖥️' pages/index.html
rg -n 'id="features"|id="how-it-works"|andcode_icon_master_1024|andcode-hero-atmosphere' pages/index.html
git diff --check
```

Expected: the first command returns no output; the second finds the two anchors and all required visual assets; `git diff --check` passes.

- [ ] **Step 7: Commit the structural/content change**

```bash
git add pages/index.html pages/*.jpg
git commit -m "feat: redesign AndCode landing page structure"
```

---

### Task 3: Add the README screenshot galleries

**Files:**
- Modify: `README.md`
- Modify: `README.ja.md`
- Verify: `screenshots/navigation-drawer.jpg`, `screenshots/model-picker.jpg`, `screenshots/repository-chat.jpg`, `screenshots/image-generation.jpg`, `screenshots/schedules.jpg`, `screenshots/run-result.jpg`

**Interfaces:**
- Consumes: the six descriptive screenshot files from Task 2.
- Produces: matching English and Japanese two-row, three-column HTML screenshot tables with captions and alt text.

- [ ] **Step 1: Replace the English README image strip with a table**

Use the OpenClaw-style HTML structure and this order:

```html
<table>
  <tr>
    <td align="center"><img src="screenshots/navigation-drawer.jpg" width="180" alt="Navigation drawer with agents, projects, and recent chats"><br><em>Navigation drawer</em></td>
    <td align="center"><img src="screenshots/model-picker.jpg" width="180" alt="Model and runtime picker with searchable favorite models"><br><em>Model &amp; runtime picker</em></td>
    <td align="center"><img src="screenshots/repository-chat.jpg" width="180" alt="Chat with an agent inspecting the AndCode repository"><br><em>Repository chat</em></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/image-generation.jpg" width="180" alt="Agent-generated image displayed inside a conversation"><br><em>Image generation</em></td>
    <td align="center"><img src="screenshots/schedules.jpg" width="180" alt="Scheduled prompt with a daily run enabled"><br><em>Schedules</em></td>
    <td align="center"><img src="screenshots/run-result.jpg" width="180" alt="Completed scheduled run with an option to open the chat"><br><em>Run result</em></td>
  </tr>
</table>
```

Keep the table inside a centered screenshot section and remove the old four-image `<p>` strip so README visitors see one canonical gallery.

- [ ] **Step 2: Mirror the gallery in the Japanese README**

Use the same image order and filenames, with Japanese captions and alt text: `ナビゲーションドロワー`, `モデル・実行先ピッカー`, `リポジトリチャット`, `画像生成`, `スケジュール`, and `実行結果`.

- [ ] **Step 3: Verify documentation links and image references**

Run:

```bash
for f in screenshots/navigation-drawer.jpg screenshots/model-picker.jpg screenshots/repository-chat.jpg screenshots/image-generation.jpg screenshots/schedules.jpg screenshots/run-result.jpg; do test -f "$f" || exit 1; done
rg -n 'screenshots/(navigation|chat|model-picker|schedules)\.png' README.md README.ja.md
git diff --check
```

Expected: the file loop succeeds; the `rg` command returns no output because the old strip was removed; `git diff --check` passes.

- [ ] **Step 4: Commit the README gallery**

```bash
git add README.md README.ja.md screenshots/*.jpg
git commit -m "docs: add descriptive AndCode screenshot galleries"
```

---

### Task 4: Validate responsive visuals, accessibility, and links

**Files:**
- Verify: `pages/index.html`, `pages/andcode-hero-atmosphere.png`, `brand/andcode_icon_master_1024.png`, the six `pages/*.jpg` screenshot assets, and both README galleries.

**Interfaces:**
- Consumes: the finished static LP.
- Produces: visual evidence at desktop and mobile widths plus a clean final diff ready for PR review.

- [ ] **Step 1: Start a local static server from the worktree**

Run:

```bash
python3 -m http.server 4173 --directory pages
```

Open `http://127.0.0.1:4173/` in the browser and inspect the rendered page, not only the source.

- [ ] **Step 2: Verify the desktop viewport**

At a desktop viewport around 1440px wide, confirm:

- the app icon appears in the header, hero product tile, and closing CTA without stretching;
- the real screenshots remain the dominant hero proof point and the generated atmosphere stays low contrast;
- navigation, CTA buttons, proof row, feature cards, timeline, and footer are visually distinct;
- no emoji appears anywhere in the feature or step UI;
- all links have the intended destinations.

Also verify the six-image README table locally by opening `README.md` in the repository viewer or using the rendered GitHub preview; each cell must show the intended screen and caption.

- [ ] **Step 3: Verify the mobile viewport**

At a mobile viewport around 390px wide, confirm:

- the header wraps or collapses without clipping;
- the hero becomes one column and screenshots remain readable;
- feature cards become one column, the step rail becomes vertical, and the CTA remains tappable;
- there is no horizontal scrollbar or text overflow.

- [ ] **Step 4: Verify semantics and interaction affordances**

Use the browser DOM/accessibility surface to confirm there is one `h1`, the feature/how-it-works anchors resolve, image alt text describes the screenshots, decorative SVGs are hidden from assistive technology, and keyboard focus is visible on links/buttons.

- [ ] **Step 5: Run final repository checks**

Run:

```bash
git diff --check
git status --short
git log -3 --oneline
```

Expected: no whitespace errors, only the intended LP files changed, and the latest commits describe the asset and page redesign.

- [ ] **Step 6: Commit any final verification-driven fixes**

If verification finds a concrete layout or accessibility defect, fix it in `pages/index.html`, rerun the relevant viewport check, and commit with:

```bash
git add pages/index.html README.md README.ja.md
git commit -m "fix: polish AndCode landing page and README layout"
```
