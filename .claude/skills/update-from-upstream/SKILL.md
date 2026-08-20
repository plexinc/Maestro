---
name: update-from-upstream
description: Use when asked to update/sync this Maestro fork with the latest upstream Maestro, rebase on upstream, or "pull in upstream changes". Rebases the fork's feature stack onto upstream/main, walks conflicts, re-verifies the fork commit stack, and force-pushes.
---

# Update From Upstream

## Overview

This repo (`plexinc/Maestro`) is a fork of [Maestro](https://github.com/mobile-dev-inc/Maestro), kept as a **minimal, linear stack of feature commits on top of `upstream/main`** — not a long-lived divergent branch. Keeping the stack small and each commit self-contained is what makes rebasing onto a fresh upstream tractable. "Updating from upstream" means **rebasing that stack onto the latest Maestro** so we pick up upstream changes while keeping our additions on top.

The fork adds, on top of upstream Maestro:

| Commit subject | Purpose |
|----------------|---------|
| `chore: setup Plex fork` | `major.minor.patch.build` versioning (`CLI_VERSION` tracks upstream; the fork-owned `PLEX_BUILD` adds a 4th segment, auto-incremented at release by `publish-cli`); update-check/changelog/install resolve straight from `plexinc/Maestro` GitHub Releases; `uninstall.sh`. Fork-only. |
| `feat(tvos): add apple tv support` | Apple TV (tvOS) driver, device handling, RN Expo tvOS demo app, and tvOS e2e flows. |
| `fix(web): prefer data-testid for element selection` | Web driver selects by `data-testid` first for stabler Lightning/WebGL selection. |
| `feat(web): expand web driver keyboard support` | Maps `REMOTE_DPAD` keycodes (arrows + center) to Selenium arrow keys. |
| `feat(web): detect web flows from a URL-shaped appId` | `FileUtils.isWebFlow()` treats an `http(s)://` `appId` as a web target. |
| `feat(web): expose focus state to the focused selector` | Emits `focused` into `TreeNode.focused` from `document.activeElement`/`data-focused`. |
| `fix(web): honor -p web when selecting the web device` | Explicit `--platform web` forces web-device inclusion. |
| `feat(cli): restore bundled Maestro Studio` | Reverts upstream's removal of the bundled Studio (#3299); restores the `maestro-studio` modules and the full `studio` command. |
| `feat(studio): make bundled Studio TV-aware with a D-pad toggle` | Studio gains a D-pad mode for TV platforms (tvOS/Vega/Fire TV) alongside touch interaction. |
| `feat(vega): add Amazon Vega (Fire TV) platform support` | `VegaDriver`, Vega device discovery/handling, and the `e2e/vega_demo_app` fixture. Unsupported primitives (`setLocation`, `setProxy`, `setDarkMode`, screen recording) throw or no-op deliberately. |
| `chore: update iOS driver [skip ci]` | Bot-generated driver artifacts (iOS + tvOS zips, tvOS `.xctestrun`) produced by the fork's `update-drivers` workflow. Regenerated automatically on push — prefer upstream's binaries on conflict and let CI rebuild. |
| `feat(orchestra): add config.yaml path aliases for flow references` | Named directory aliases under `paths:` in a workspace `config.yaml`, referenced as `"@alias/rest"`, resolved via the nearest config.yaml walking up from the flow. Replaces long `../../..` chains. Lives in `FlowPathResolver.kt`. |
| `feat(orchestra): add readFile command and document fork additions` | `readFile` loads a JSON file into a namespaced object variable (`${data.field}`); adds object-typed JS bindings (`JsEngine.putObjectEnv`) via GraalJS proxies. Also introduces `FORK.md`. |
| `feat(web): Support attaching to a running Chrome/Electron over CDP` | Attach mode (`--cdp-url` / `--cdp-target`) drives an already-running webview instead of launching Chrome; input routed over CDP since there is no Selenium session. |
| `chore(fork): add verify-fork-stack skill and resync fork docs` | Fork tooling: the `verify-fork-stack` skill plus the `FORK.md` / stack-table reconciliation it enforces. |

This table is the **fork commit stack** — keyed on commit subject, not SHA (rebasing rewrites SHAs, subjects are stable). Keep it in sync with `git log --oneline upstream/main..main`.

## When to Use

- User asks to sync/update the fork with upstream Maestro, "rebase on upstream", or pull in upstream changes.

## Pre-flight

- Working tree clean (`git status`).
- Remotes present (`git remote -v`): `origin` → `plexinc/Maestro`, `upstream` → `mobile-dev-inc/Maestro`. Add `upstream` if missing:
  ```bash
  git remote add upstream https://github.com/mobile-dev-inc/Maestro.git
  ```
- Snapshot the current tip so the rebase is recoverable:
  ```bash
  git branch -f fork-backup main
  ```

## Procedure

1. **Fetch upstream:**
   ```bash
   git fetch upstream
   ```

2. **Inspect the current fork stack** (this is what gets replayed) and cross-check subjects against the table above:
   ```bash
   git log --oneline upstream/main..main   # the fork stack (still on the OLD base until rebase)
   git log --oneline main...upstream/main  # divergence overview
   ```

3. **Rebase onto the new upstream:**
   ```bash
   git switch main
   git rebase upstream/main
   ```

4. **Resolve conflicts.** Common offenders:
   - **Checked-in driver binaries** — `maestro-ios-driver/src/main/resources/**/*.zip` and `*.xctestrun` (incl. the tvOS `driver-appletvSimulator/**`). Binary conflicts: keep the fork's version unless upstream rebuilt the driver, then prefer upstream and re-verify the tvOS additions still apply. The tvOS `maestro-driver-tvos-config.xctestrun` must not regain a `CodeCoverageBuildableInfos` block with machine-absolute source paths — drop it if a rebuild reintroduces one.
   - **`maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj/project.pbxproj`** — frequently rewritten upstream; reapply the tvOS target/scheme entries by hand if needed.
   - **`maestro-cli/gradle.properties`** — keep the `PLEX_BUILD` line; take upstream's `CLI_VERSION` bump.
   - **`maestro-cli/build.gradle.kts`** — keep the `FULL_CLI_VERSION`/`PLEX_BUILD` wiring and the `plexinc/Maestro` jreleaser release block.
   - **`maestro-cli/src/main/java/maestro/cli/api/ApiClient.kt`** — keep `CliVersion`'s `build` segment / `baseVersion` / 4-part parsing.
   - **`mapToSeleniumKey()`** and focus handling in `WebDriver.kt` / `CdpWebDriver.kt` — keep the `REMOTE_DPAD` branches and the `focused` wiring.

   After resolving each step: `git add <files> && git rebase --continue`. Abort with `git rebase --abort` (then `git reset --hard fork-backup`) if it goes sideways.

5. **Re-verify the stack.** Every subject in the table above must still map 1:1 to a commit in `git log --oneline upstream/main..main`. If upstream merged one of our features (so the commit dropped out during rebase), delete that row from the table here. Also refresh the commit SHAs cited in `FORK.md` — the rebase rewrote all of them. The `verify-fork-stack` skill automates this reconciliation.

6. **Build check** (fast sanity):
   ```bash
   ./gradlew :maestro-cli:compileKotlin :maestro-client:compileKotlin
   ```

7. **Push the updated fork** (rebase rewrites history, so force is required) — only after asking the user:
   ```bash
   git push --force-with-lease origin main
   ```

## Notes

- Only force-push `origin` after the rebase is clean and the stack is reconciled, and only when explicitly asked.
- Keep the stack minimal: prefer amending an existing fork commit over adding a new one when a change belongs to an existing feature.
- Never add `Co-Authored-By` or tool-attribution trailers to commit messages.
