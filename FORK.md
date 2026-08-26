# FORK.md — Plex fork additions

Reference for everything `plexinc/Maestro` adds on top of upstream
[mobile-dev-inc/Maestro](https://github.com/mobile-dev-inc/Maestro). If a flow
uses syntax, a CLI flag, or a platform that isn't in upstream Maestro's docs,
it's listed here.

**Read this before writing flows, tests, or docs** — these features are
fork-only and won't appear in upstream references.

**Maintenance rule:** every fork-only feature must be documented here. When you
add a capability on top of upstream (new command, selector, platform, CLI flag,
config key), add an entry in the same commit. The `README.md` "What this fork
adds" section is the marketing summary; this file is the developer/agent
reference. Keep them consistent.

**Fork boundary:** commit `e618462a chore: setup Plex fork` is the first
fork commit. Everything reachable above it (and not authored by the upstream
sync) is fork-specific. To list the current stack:
`git log --oneline e618462a^..HEAD`.

---

## Flow commands & syntax

### `readFile` — load a JSON file into a namespaced variable

Reads a JSON file from disk and exposes its parsed contents as a single named
object variable, so later commands and scripts reference fields directly
instead of hardcoding data.

```yaml
appId: com.example.app
---
- readFile:
    file: data/testdata.json      # resolved relative to the flow (or via @alias, see below)
    outputVariable: data
- tapOn:
    id: ${data.login.testId}
- inputText: ${data.queries[0]}
```

- Multiple reads coexist under distinct `outputVariable` names:
  ```yaml
  - readFile: { file: movies.json, outputVariable: moviesData }
  - readFile: { file: shows.json,  outputVariable: showData }
  ```
- **JSON only.** Invalid JSON fails the command with a clear parse error.
- The file is read at **parse time** (like `runScript`), so the `file:` path
  cannot be `${}`-interpolated (dynamic paths are unsupported).
- Nested objects/arrays resolve natively (`${data.a.b[0]}`) because the parsed
  tree is wrapped as GraalJS `ProxyObject`/`ProxyArray`.
- `outputVariable` **cannot** be a reserved JS binding
  (`http`, `faker`, `output`, `maestro`, `json`, `relativePoint`) — rejected at
  parse time with a `SyntaxError`. Reusing another `readFile`'s variable name is
  allowed (it overwrites, like any reassignment).

Code: `ReadFileCommand` (`maestro-orchestra-models/.../Commands.kt`),
`YamlReadFile.kt` + `readFile` branch in `YamlFluentCommand.kt`,
`Orchestra.readFileCommand`, and `JsEngine.putObjectEnv` /
`GraalJsEngine.RESERVED_BINDING_KEYS`.

### `config.yaml` path aliases — `@alias/...` flow references

Named directory aliases declared once in the workspace `config.yaml`, so shared
flows don't need long `../../../../` chains. Resolution walks up from the flow
file to the nearest `config.yaml`, so top-level and nested sub-flows behave
identically.

```yaml
# config.yaml
flows:
  - "**/*.yaml"
paths:
  commands: ./shared/commands   # dir relative to this config.yaml (or absolute)
```

```yaml
# any flow, at any depth
- runFlow: "@commands/login.yaml"   # quote it — @ is a YAML reserved indicator
```

- Applies to `runFlow`, `retry`, `runScript`, and `addMedia`, plus
  dependency-watching. Non-`@` paths keep the existing sibling-relative behavior.
- Unknown alias or a target that isn't an existing directory → `SyntaxError`.

Code: `FlowPathResolver.kt`, `paths` on `WorkspaceConfig.kt`,
`YamlFluentCommand.resolvePath`. Commit `08fe23ed`.

---

## Platforms

### Apple TV (tvOS)

Full tvOS driver + device management. Platform id `TVOS` (`--platform tvos`).

```bash
maestro start-device --platform tvos
maestro test --platform tvos flow.yaml
```

Includes a tvOS RN/Expo demo app and e2e flows under `e2e/tvos_demo_app/` and
`e2e/workspaces/tvos_*`. Commit `a220b2a0`.

### Amazon Vega / Fire TV

Driver for Amazon's Vega OS (a Linux/React Native OS — **not** Android).
Platform id `VEGA`, driven through the `vega`/`vda` toolchain: view hierarchy
from the on-device automation toolkit, D-pad/touch/swipe/text input, screenshots.

Demo app + flows under `e2e/vega_demo_app/` and `e2e/workspaces/vega_demo_app/`.
Code: `DeviceSpec.Vega`, `maestro/device/DeviceService.kt`, `VegaLocale`.
Commit `1743bfdf`.

### Roku

Contributed by the [Nami](https://www.nami.ml) team ([rku.dev](https://www.rku.dev)).

Driver for Roku devices over the **External Control Protocol** (ECP — an HTTP
REST API on device port 8060). Platform id `ROKU` (`--platform roku`). No
on-device driver process: view hierarchy from `/query/app-ui` (SceneGraph XML,
dev-mode channels only), D-pad input via `/keypress/<key>`, text via
character-by-character `LIT_` keypresses, screenshots via the dev web server
(digest auth), app launch via `/launch/<channelId>`. Roku is D-pad-only — `tapOn`
sends `Select` (activates the focused element), swipes/scrolls become repeated
D-pad presses in the direction the *content* moves — `swipe: UP` reveals what is
below it, so it presses Down, and `scroll` is `swipe: UP`, as on Vega and web.
`launchApp` is a cold launch: a channel that is already running is exited to the
home screen first so it restarts from its initial state, and it fails the flow if
the channel never becomes the active app. A launch (or an `openLink` deep link)
carries only the parameters the flow asked for.

A rejected ECP command fails the flow rather than logging a warning — otherwise a
device with ECP access set to anything but Permissive serves `/query/app-ui` while
403-ing every keypress, and a flow passes green having never touched the device.
The 403 is reported with that setup hint. Nodes the device isn't rendering
(`visible="false"`, `opacity="0"`) are dropped from the hierarchy with their
subtrees, so `assertVisible` won't match a hidden element.

**Device setup** (physical hardware only — Roku has no emulator):

1. Enable developer mode: Home 3x, Up 2x, Right, Left, Right, Left, Right; set a
   dev password.
2. Set ECP network access to Permissive: Settings > System > Advanced system
   settings > Control by mobile apps > Network access > **Permissive** (recent
   Roku OS versions return 403 on input commands otherwise).

**Device selection** — Roku devices surface through the normal device listing:

```bash
export MAESTRO_ROKU_HOST=192.168.1.100      # pin a device by IP (primary)
export MAESTRO_ROKU_PASSWORD=devpwd          # dev-mode password (screenshots only)
export MAESTRO_ROKU_DISCOVERY=true           # optional: SSDP LAN scan (~1s per listing)

maestro test --platform roku flow.yaml
maestro test --device 192.168.1.100 flow.yaml
```

**New KeyCodes** (also mapped on Android): `Remote Info` (the `*` options
button), `Remote Instant Replay`, `Remote Search`. `Remote Menu` maps to `Info`
on Roku. Studio's TV mode is auto-on for Roku, like tvOS.

Demo channel + flows under `e2e/roku_demo_app/` (BrightScript; sideload steps in
the flow headers) and `e2e/workspaces/roku_demo_app/` — mirrors the tvOS/Vega
demo apps (same screens, labels, and testIDs: Home menu, 2x2 navigation grid,
native-Keyboard text input, programmatic-focus test).
Code: `maestro/roku/` (`RokuEcpClient`, `RokuDeviceDiscovery`, `RokuAppUIParser`,
`RokuKeyMapping`), `drivers/RokuDriver.kt`, `DeviceSpec.Roku`, `RokuLocale`.

### Web driver enhancements (canvas / D-pad TV web apps)

For D-pad-driven or WebGL/canvas web apps (e.g. Lightning). Platform id `WEB`.

- **`data-testid`-first `resource-id`.** Element `resource-id` prefers
  `data-testid`, then `id`/`ariaLabel`/`name`/title — so `id:` selectors match
  the stable test id. (`maestro-web.js`, commit `59a68e5a`.)
- **`focused:` selector populated.** The web driver reports focus from
  `document.activeElement` and from a `data-focused="true"` flag (for canvas
  DOM-inspector bridges whose `activeElement` is always the `<canvas>`).
  (`maestro-web.js`, commit `4e344da2`.)
- **D-pad / remote keys.** `KeyCode.REMOTE_{UP,DOWN,LEFT,RIGHT}` map to arrow
  keys, `REMOTE_CENTER`→Enter, `REMOTE_MENU`→Escape (back). (`WebDriver.kt`,
  `CdpWebDriver.kt`, commit `9314116d`.)
- **URL-shaped `appId` ⇒ web target.** An `appId` starting with `http://` /
  `https://` is auto-detected as a web flow (no separate `url:` needed).
  (`FileUtils.kt`, commit `7f36a70c`.)
- **`--platform web` honored** when selecting the device. (`TestCommand.kt`,
  commit `782c6b0b`.)
- **Attach to a running browser.** `--cdp-url` (plus optional `--cdp-target`)
  drives an already-running Chrome/Electron webview instead of launching Chrome
  via Selenium — e.g. one specific Lightning emulator tile. The target is
  resolved by explicit id, else by matching `appId` against target URLs. Input
  is dispatched over CDP in this mode; the launched-Chrome path is unchanged.
  (`TestCommand.kt`, `CdpWebDriver.kt`, `CdpClient.kt`, commit `804fc908`.)

---

## Tooling & distribution

### Bundled Maestro Studio (restored, TV-aware)

Re-adds the local `maestro studio` web IDE that upstream removed
(`maestro-studio/` server + web). Adds a **TV mode**: auto-on for tvOS,
toggleable for web, where taps/swipes and physical arrow keys / Enter / Esc
drive the D-pad / select / back.

```bash
maestro studio
```

Commits `aea64004` (restore), `12d813d1` (TV-aware D-pad toggle).

### `major.minor.patch.build` versioning

`CLI_VERSION` tracks upstream Maestro; a fork-owned `PLEX_BUILD` segment
(`maestro-cli/gradle.properties`) lets the fork ship builds without drifting
from the inherited upstream version. Commit `e618462a`.

### GitHub-direct distribution

Install/uninstall scripts, CLI self-update, and release resolution all point at
`plexinc/Maestro` releases (no external proxy):

```bash
curl -fsSL "https://raw.githubusercontent.com/plexinc/Maestro/main/scripts/install.sh" | bash
curl -fsSL "https://raw.githubusercontent.com/plexinc/Maestro/main/scripts/uninstall.sh" | bash
```

Code: `scripts/install.sh`, `scripts/uninstall.sh`, `Updates.kt`, `ApiClient.kt`,
`EnvUtils.kt`. Commit `e618462a`.

---

## Keeping the fork in sync

The fork stack is rebased onto upstream via the `update-from-upstream` skill
(`.claude/skills/update-from-upstream/`). After a rebase, re-verify every entry
above still reflects reality — the `verify-fork-stack` skill
(`.claude/skills/verify-fork-stack/`) does this reconciliation.

Note the commit SHAs cited above are rewritten by every rebase, so they must be
re-pointed at the commit with the same subject as part of the sync.
