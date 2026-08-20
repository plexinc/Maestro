---
name: verify-fork-stack
description: Use when asked to check/verify/audit the Plex fork's commit stack, confirm the fork docs are in sync, or sanity-check the fork before cutting a release or opening a PR. Reconciles `git log upstream/main..main` against the FORK.md and update-from-upstream tables, checks fork-owned invariants, and reports drift with fixes.
---

# Verify Fork Stack

## Overview

`plexinc/Maestro` is a **linear stack of feature commits on top of `upstream/main`**
(see [update-from-upstream](../update-from-upstream/SKILL.md)). Three things drift
apart over time and nothing else catches it:

- The stack itself vs. the table in `update-from-upstream/SKILL.md`.
- The stack vs. `FORK.md`, whose maintenance rule says *every* fork-only feature
  must be documented in the same commit that adds it.
- Commit SHAs cited in `FORK.md` — **every rebase rewrites them**, so they go
  stale silently.

Run this standalone: after a rebase, before a release, or whenever the fork's
additions need to be trusted. It is **read-only by default** — report drift, then
fix only what the user approves.

## When to Use

- "Is the fork stack still intact / clean?", "audit the fork", "check FORK.md is up to date".
- Before `prepare-release` or a `publish-cli` dispatch.
- After `update-from-upstream`, as an independent second pass.

## Procedure

### 1. Establish the stack

```bash
git fetch upstream
git rev-list --left-right --count upstream/main...main   # left=behind, right=stack size
git log --oneline --no-decorate upstream/main..main
```

Record the subjects — subjects are the stable identity, SHAs are not.

**Check:** left count is 0 (fully rebased). If not, the fork is behind and the
rest of this audit is provisional — say so and suggest `update-from-upstream`.

### 2. Reconcile against the update-from-upstream table

Compare the subjects from step 1 one-to-one with the table rows in
`.claude/skills/update-from-upstream/SKILL.md`.

- Subject in the stack, missing from the table → **add a row** describing it.
- Row in the table, missing from the stack → the commit was dropped or upstream
  merged the feature → **delete the row** (confirm it really is upstream first:
  `git log --oneline upstream/main --grep "<subject>"`).
- Counts must match. State both numbers explicitly in the report.

### 3. Reconcile against FORK.md

Every fork-only user-facing capability (command, selector, platform, CLI flag,
config key) needs an entry. Cross-check each stack commit:

```bash
git show --stat <sha>          # what did it actually add?
grep -n "^## \|^### " FORK.md  # what is documented?
```

Flag any feature commit whose capability has no `FORK.md` entry. Pure-artifact
commits (`chore: update iOS driver`) and internal refactors are exempt — only
things a flow author could *use* need documenting.

Also check `README.md`'s "What this fork adds" summary still matches; `FORK.md`
is the reference, the README is the marketing view.

### 4. Verify SHAs cited in FORK.md

`FORK.md` cites commits like ``(`maestro-web.js`, commit `f6e32814`.)``. A rebase
invalidates all of them. For each cited SHA:

```bash
git cat-file -e <sha>^{commit} 2>/dev/null   # does it still exist?
git merge-base --is-ancestor <sha> main      # is it still in the stack?
```

A SHA that fails either check is stale. The fix is to re-point it at the commit
with the matching subject in the current stack — these SHAs should be refreshed
as part of every rebase.

### 5. Check fork-owned invariants

Things the fork owns that a bad conflict resolution silently drops:

| Invariant | Where | Check |
|---|---|---|
| `PLEX_BUILD` line survives; `CLI_VERSION` equals upstream's | `maestro-cli/gradle.properties` | `diff <(git show upstream/main:maestro-cli/gradle.properties) maestro-cli/gradle.properties` — only the `PLEX_BUILD` block should differ |
| `VERSION_NAME` matches `CLI_VERSION` | `gradle.properties` | grep both |
| 4-segment version parsing | `ApiClient.kt` (`CliVersion`) | `build` segment + `baseVersion` present |
| jreleaser points at `plexinc/Maestro` | `maestro-cli/build.gradle.kts` | grep `plexinc` |
| Updates/changelog resolve from the fork's releases | `Updates.kt`, `ChangeLogUtils.kt` | grep `plexinc` |
| `REMOTE_DPAD` mapping + `focused` wiring | `WebDriver.kt`, `CdpWebDriver.kt` | grep `REMOTE_` / `focused` |
| Every `Driver` interface member implemented by fork drivers | `VegaDriver.kt`, tvOS paths | see step 6 — upstream adding an interface method breaks fork-only drivers |

### 6. Compile check

Upstream regularly adds members to the `Driver` interface; fork-only drivers
(`VegaDriver`) don't get updated by that commit, so this is the single most
likely real breakage:

```bash
./gradlew :maestro-cli:compileKotlin :maestro-client:compileKotlin
```

Gradle rewrites driver artifacts and `package-lock.json` as a side effect —
restore them afterwards so the audit doesn't dirty the tree:

```bash
git checkout -- maestro-ios-driver/src/main/resources/ maestro-cli/mcp-viewer/package-lock.json
```

### 7. Report

Report as a short list of drift items, each with the fix. Then ask before
changing anything. Nothing in this skill commits, amends, or pushes.

## Notes

- Read-only by default. Doc fixes are cheap and usually worth offering; commit
  surgery (folding a fix into its feature commit) needs explicit approval.
- Keep the stack minimal: a new fix belongs **amended into** the feature commit
  it repairs, not stacked on top.
- Never add `Co-Authored-By` or tool-attribution trailers.
