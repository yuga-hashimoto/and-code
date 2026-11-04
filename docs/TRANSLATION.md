# Translation Guide

## README Translations

The README is translated into every language the app UI supports. English
[`README.md`](../README.md) is the source of truth; when a section changes there, update the
matching section in each translation in the same pull request.

| File | Language |
|------|----------|
| [`README.md`](../README.md) | English (source) |
| [`README.ja.md`](../README.ja.md) | Japanese |
| [`README.zh-CN.md`](../README.zh-CN.md) | Chinese (Simplified) |
| [`README.es.md`](../README.es.md) | Spanish |
| [`README.fr.md`](../README.fr.md) | French |
| [`README.pt-BR.md`](../README.pt-BR.md) | Portuguese (Brazil) |
| [`README.ru.md`](../README.ru.md) | Russian |
| [`README.ar.md`](../README.ar.md) | Arabic |

Keep the language switcher line near the top of every README identical, and keep relative links
(e.g. `docs/CI.md`, `LICENSE`) unchanged so they resolve from the repository root. Headings may be
translated, but update the table-of-contents anchors to match. Code blocks, commands, URLs, version
numbers, and third-party product names stay as-is.

### Adding a README language

1. Copy `README.md` to `README.<lang>.md` (use the same locale code as `values-<lang>/`).
2. Translate the prose, keeping structure, links, commands, and legal wording intact.
3. Add the language to the switcher line in **every** existing README.
4. Add the file to the table above.

## Contributing via Weblate

The easiest way to contribute translations is through our [Weblate instance](https://hosted.weblate.org/projects/and-code/).

1. Create a free Weblate account (or use GitHub/Google sign-in).
2. Navigate to the **AndCode** project.
3. Pick a language and start translating strings.
4. Weblate automatically opens a pull request with your changes.

## Contributing via Pull Request

1. Fork the repository.
2. Edit or create `app/src/main/res/values-<lang>/strings.xml`.
3. Ensure every `<string name="...">` key matches the source file (`values/strings.xml`).
4. Open a PR with your changes.

## File Structure

```
app/src/main/res/
├── values/strings.xml          # Source (English)
├── values-ar/strings.xml       # Arabic
├── values-es/strings.xml       # Spanish
├── values-fr/strings.xml       # French
├── values-ja/strings.xml       # Japanese
├── values-pt-rBR/strings.xml   # Portuguese (Brazil)
├── values-ru/strings.xml       # Russian
└── values-zh-rCN/strings.xml   # Chinese (Simplified)
```

## Currently Supported Languages

| Code      | Language              |
|-----------|-----------------------|
| en        | English (source)      |
| ar        | Arabic                |
| es        | Spanish               |
| fr        | French                |
| ja        | Japanese              |
| pt-rBR    | Portuguese (Brazil)   |
| ru        | Russian               |
| zh-rCN    | Chinese (Simplified)  |

## Adding a New Language

1. Create a directory: `app/src/main/res/values-<lang>/`
   - Use ISO 639-1 codes (e.g. `ko`, `de`, `it`).
   - For regional variants use `-r` (e.g. `values-pt-rPT`).
2. Copy `values/strings.xml` into the new directory.
3. Translate the string values (keep the `name` attributes unchanged).
4. Remove any strings you have not yet translated — Android falls back to English automatically.
5. Open a PR or submit via Weblate.

## String Naming Conventions

- Use `snake_case` for all string names.
- Prefix with the feature or screen area:
  - `nav_` — navigation items
  - `chat_` — chat screen
  - `workspace_` — workspace management
  - `provider_` — provider/auth flows
  - `notification_` — notifications
  - `voice_` — voice features
  - `settings_` / `section_` — settings screens
  - `drawer_` — navigation drawer
  - `mcp_` — MCP server management
  - `schedule_` — scheduled tasks
  - `onboarding_` — onboarding flow
  - `remote_` — remote connection setup
  - `github_` — GitHub integration
  - `install_step_` — local runtime installation
  - `runtime_status_` — runtime status labels
- Use `_title`, `_body`, `_hint`, `_label`, `_description`, `_button` suffixes for UI element types.
- Formatted strings use positional placeholders: `%1$s`, `%2$d`, etc.
