# Changelog

## [Unreleased]

- Bump `eca-webview`: chat prompt area now renders flat (no border, no rounded corners, no drop-shadow) inside the IntelliJ tool window. The modal-card treatment recently added for `eca-desktop`'s wide window looked out of place in narrow side panels, so it's now scoped to `eca-desktop` only.

## 0.26.2

- Hide the JCEF webview "Open DevTools" right-click entry in production builds (now gated by `config/dev?`); the toolbar DevTools button remains dev-only as before.
- Fix broken tool-window `icon` reference in `plugin.xml` that pointed to a non-existent class; now points to `dev.eca.eca_intellij.Icons.ECA_LIGHT`.
- Inject missing IntelliJ theme tokens (tooltip, accent, success/warning/error, confirm-action, toggle, context, diff) so the chat UI follows the IDE's light theme. Closes #14.
- Bump `eca-webview`: chat sub-header buttons now show hover tooltips in JCEF (native HTML `title=` is suppressed by the embedded browser); `<ToolTip>` popovers (MCPs, MCP-settings tool descriptions, usage details, etc.) now follow the IDE theme on light themes; trust toggle uses flame for ON and shield-with-check for OFF to match `eca-emacs`; pulls in upstream iOS/mobile UX improvements.

## 0.26.1

- Fix Windows server binary being saved without `.exe` extension, which prevented the auto-downloaded server from launching without a manual rename workaround.
- Sync the chat trust indicator from the server on chat resume by bumping `eca-webview` to honor `selectTrust` on `config/updated` (eca #426).

## 0.26.0

- Add Settings → Global Config tab with inline JSONC editor (validates + writes atomically).
- Add Settings → Logs tab streaming live server stderr and lifecycle events.
- Settings tab-bar now scrolls horizontally inside itself instead of widening the whole webview on narrow tool-window widths.
- Global Config JSON editor uses a VS Code-style highlight palette for better readability across light and dark themes.

## 0.25.16

## 0.25.15

- Add chat/askQuestion support for inline questions.

## 0.25.14

- Trust mode can now be toggled via chat/update and applies immediately to the next tool call without requiring a new prompt.

## 0.25.13

## 0.25.12

- Add background jobs support (jobs/updated notification, jobs panel in settings, inline status icons, output viewer).

## 0.25.11

- Add chat flag support with fork and remove actions.

## 0.25.10

- Add steer prompt support for redirecting running prompts at the next LLM turn boundary.

## 0.25.9

## 0.25.8

## 0.25.8

- Add Settings page with MCPs, Providers, and Global Config tabs.

## 0.25.7

- Add MCP server disable/enable from details page.

## 0.25.6

## 0.25.5

- Fix mobile approval UI showing in editor panels.

## 0.25.4

## 0.25.3

- Sync chat deletions

## 0.25.2

- Improve rendering of user messages and codeblocks.

## 0.25.1

- Fix UI for light themes.

## 0.25.0

- UI Overhaul

## 0.24.0

- Support update mcps via mcp page.

## 0.23.0

- Support connect/logout mcp servers.

## 0.22.0

- Support eca__task UI.

## 0.21.0

- Support editor/getDiagnostics from server.
- Support querying files via # in prompt.
- Support prompt queueing.
- Support export chat to markdown file.
- Support prompt history
- Support image copy paste
- Support chat rename
- Support chat timeline navigation.

## 0.20.0

- Support for model variants.

## 0.19.2

- Minor light theme improvements

## 0.19.1

- Fix for when no models are available, allowing login.

## 0.19.0

- Add support for subagents.

## 0.18.1

- Improve usage UI.

## 0.18.0

- Rename behavior -> agent matching server.

## 0.17.3

- Fix scroll on models selection

## 0.17.2

- open markdown links in browser.

## 0.17.1

- Fix diffs crashing for new files.

## 0.17.0

- Support rollback messages in chat

## 0.16.1

## 0.16.0

- Support command to add context to system prompt.

## 0.15.1

- Bump wevbiew adding shortcut to tool call approval.
- Avoid sending multiple prompts while one is loading.

## 0.15.0

- Bump webview supporting json outputs

## 0.14.2

- Improve icon for dark themes.

## 0.14.1

- Fix @cursor context correct tracking position/file.

## 0.14.0

- Support hooks in chat.

## 0.13.0

- Support opening global config file from chat window.

## 0.12.0

- Support Accept and remember on tool call.

## 0.11.2

- Improve user shel env to spawn a interactive shell to get env.

## 0.11.1

- Improve logging for env used in eca process.

## 0.11.0

- Improve @cursor context ui.

## 0.10.1

- Avoid exceptions when checking logs.

## 0.10.0

- Support prompts args input.

## 0.9.0

- Remove deprecated repoMap as defaultContext.

## 0.8.2

- Add Intellij client-info to server initialize.

## 0.8.1

- Support increase/decrease font size. #2

## 0.8.0

- Support chat title.

## 0.7.5

- Improve ECA to use user shell for getting envs.

## 0.7.4

- Improve chat commands completion.

## 0.7.3

- Fix exceptions when focusing outside chat.

## 0.7.2

- Improve welcome message.

## 0.7.1

- Minor theme improvements.

## 0.7.0

- Support @cursor context.

## 0.6.3

- Support empty reason blocks in UI.

## 0.6.2

- Improve markdown code in chat.

## 0.6.1

- Improve theme colors.

## 0.6.0

- Support time on reason and tool call blocks.

## 0.5.5

- Bump webview: support toolCallRunning content.

## 0.5.4

- Improve tool call block header.

## 0.5.3

- Improve chat performance of long tool calls and reason freezing editor.

## 0.5.2

- Fix group id for eca actions

## 0.5.1

- Bump eca-webview.

## 0.5.0

- Add support for stderr logs access.
- Improve debugging MCP servers failed.

## 0.4.2

- Bump `eca-webview`.

## 0.4.1

- Fix regression.

## 0.4.0

- Support `config/updated` from server.

## 0.3.0

- Support usage string format in settings.

## 0.2.3

- Fix docs open in chat.

## 0.2.2

- Fix corner case where server starts but webview is not notified.

## 0.2.1

- Update plugin logos.

## 0.2.0

- Add support for tabs in chats.

## 0.1.4

- Improve color theme for light editors.

## 0.1.3

- Fix corner case where webview sends message even if js is not ready.

## 0.1.2

- Bump webview: improving prompt select-boxes.

## 0.1.1

- Fix change of selected model/behavior

## 0.1.0

- Refresh files when tool called

## 0.0.3

- Support file focus integration

## 0.0.2

## 0.0.1

- First release
