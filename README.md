# ECA IntelliJ

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

<!-- Plugin description -->

[Free OpenSource Intellij plugin](https://github.com/editor-code-assistant/eca-intellij) with support for AI pair programming via [ECA](https://eca.dev)

<!-- Plugin description end -->

![demo](demo.gif)

---

ECA (Editor Code Assistant) IntelliJ is an AI-powered pair-programming client for IntelliJ.
It connects to an external `eca` server process to provide interactive chat, code suggestions, context management and more.

For more details about ECA, features and configuration, check [ECA server](https://eca.dev).

This extension will auto download `eca` and manage the process.

## Settings

Go to Preferences > Tools > ECA.

## Troubleshooting

Check [troubleshooting](http://eca.dev/troubleshooting) docs section.

## Development

## Webview

To start the [eca-webview](https://github.com/editor-code-assistant/eca-webview):

```bash
bb dev-webview
```

This will start Vite dev server on `http://localhost:5173`, so any changes will be updated on the IntelliJ live.

## Plugin

`bb install-plugin <pathToYourIntellij>` to install the plugin locally.

or

`bb build-plugin` to manually install via intellij the .zip.
