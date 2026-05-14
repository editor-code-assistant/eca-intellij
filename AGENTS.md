- Always make sure there is no reflection warnings and that bb build-plugin works after all changes were done.

## Tests

- `bb test` runs the full Clojure suite via `./gradlew test` → `clojureTest` (JavaExec). The IntelliJ Platform plugin retrofits the stock `Test` task with JBR + PathClassLoader, which breaks `clojure.lang.RT`; the JavaExec route bypasses that.
- Clojure tests live under `src/test/clojure/dev/eca/eca_intellij/`. Filename suffix `_test.clj` is required for auto-discovery by the runner at `src/test/clojure/eca_intellij/test_runner.clj`.
- Shared fixtures (`test_fixtures.clj`) provide `with-test-project`, `with-stub-bridge`, and helpers like `sent-to-webview` / `sent-to-server` / `stub-reply!` so deftests can drive `webview/handle` without a real JCEF browser or ECA server.
- Every bugfix should land with a named regression test. Pin the commit hash in the docstring so future readers know why the case exists.
