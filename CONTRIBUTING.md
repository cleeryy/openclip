# Contributing

This document describes **the working method shared by every repository**. It is
deliberately language- and tool-agnostic: it states rules, not project-specific
commands. Each repository may extend it, but must never contradict it.

Goal: a readable Git history, fast reviews, and `main` that is always deployable.

> **If you remember one thing:** one branch = one subject, one commit = one
> coherent change, one Pull Request = an intention still readable in six months.

---

## Table of contents

1. [Golden rules](#1-golden-rules)
2. [Getting started](#2-getting-started)
3. [Branches](#3-branches)
4. [Commits](#4-commits)
5. [Quality before pushing](#5-quality-before-pushing)
6. [Pull Requests](#6-pull-requests)
7. [Code review](#7-code-review)
8. [Merging](#8-merging)
9. [Versions and releases](#9-versions-and-releases)
10. [Repository hygiene](#10-repository-hygiene)
11. [Security](#11-security)
12. [Issues and proposals](#12-issues-and-proposals)
13. [Checklist before opening a PR](#13-checklist-before-opening-a-pr)
14. [Adapting this document to a repository](#14-adapting-this-document-to-a-repository)

---

## 1. Golden rules

1. **`main` is always green.** It builds, passes tests, and can be shipped at any
   time.
2. **Never push directly to `main`.** Everything goes through a branch + Pull
   Request. (Maintainers may deviate for a security or production fix, and must
   say so in the PR afterwards.)
3. **One branch, one subject.** No "I'll sneak this in": that becomes another
   branch.
4. **Atomic, reviewable commits.** One commit = a change that makes sense on its
   own and could be reverted without breaking anything else.
5. **Small is fast.** A PR under ~400 useful lines is reviewed in 15 minutes;
   beyond 1000 lines it will be poorly reviewed. Split it.
6. **Behaviour is documented.** Any non-obvious decision (technical choice,
   workaround, accepted debt) belongs in the code, the commit, or the PR — not in
   a lost chat thread.
7. **Nothing secret, nothing generated, nothing heavy** enters the repository
   (see §10 and §11).

---

## 2. Getting started

```bash
git clone <repository-url>
cd <repository>
```

Every repository documents its install, test and lint commands in its
`README.md`. General rule: **if you cannot run the tests with one command, that
is a repository bug** — open an issue.

Recommended Git configuration (once per machine):

```bash
git config --global pull.rebase true          # linear history on pull
git config --global fetch.prune true          # drop deleted remote branches
git config --global rebase.autoStash true     # avoid losing uncommitted work
git config --global core.autocrlf input       # Windows/Linux consistency (if the repo has no .gitattributes)
git config --global user.name  "First Last"
git config --global user.email "first.last@organisation.com"
```

---

## 3. Branches

### Naming

```
<type>/<ticket>-<short-kebab-case-description>
```

- `type`: identical to the commit types (`feat`, `fix`, `docs`, `refactor`,
  `test`, `chore`, `perf`, `ci`, `build`).
- `ticket`: the issue/ticket id, omitted when there is none.
- `description`: 3 to 5 lowercase words separated by hyphens.

Examples:

```
feat/412-export-csv
fix/auth-token-expires-too-late
docs/update-readme-installation
refactor/split-billing-service
chore/bump-dependencies
```

Rules:

- **Short-lived branch** (a few days). If it lives longer than a week, split it
  or decide.
- Always start from an up-to-date `main`, never from another working branch.
- A merged branch is **deleted** (remote included).
- Never use generic names: `dev`, `test`, `patch`, `v2`, `wip`, `my-branch`.

### Staying up to date

Rebase rather than merge, to keep history linear:

```bash
git fetch --prune
git rebase origin/main
```

On conflict: resolve, `git add`, `git rebase --continue`. **Never merge `main`
into your feature branch.**

---

## 4. Commits

### Format: Conventional Commits

```
<type>(<optional scope>): <imperative description>

<optional body: the why, not the how>

<optional footer: BREAKING CHANGE, refs, co-authors>
```

- **Title line ≤ 72 characters**, no trailing period, present imperative
  ("add", not "added" or "adding").
- The title describes **the effect**, not the touched file:
  `fix(api): reject an expired token`, not `fix(api): change token.ts`.

### Types

| Type       | Usage                                                        |
| ---------- | ------------------------------------------------------------ |
| `feat`     | New user-visible capability                                  |
| `fix`      | Bug fix                                                      |
| `docs`     | Documentation only (README, comments, this file)             |
| `style`    | Formatting, whitespace, semicolons — **no change in meaning** |
| `refactor` | Code reorganisation without behaviour change                 |
| `perf`     | Performance improvement                                      |
| `test`     | Adding or fixing tests                                       |
| `build`    | Dependencies, build tooling                                  |
| `ci`       | Continuous integration configuration                         |
| `chore`    | Miscellaneous maintenance (versions, scripts, cleanup)       |

### Scopes

Optional but recommended as soon as the repository has several modules:
`engine`, `app`, `ui`, `jni`, `ci`, `deps`, `docs`… The list lives in the
repository `README.md`.

### Examples

```
feat(export): add CSV export for invoices

fix(auth): reject an expired token instead of refreshing it

refactor(parser): isolate header parsing

docs: clarify the restore procedure

perf(list): paginate server-side beyond 500 items
```

Breaking change:

```
feat(api)!: remove the /v1/users endpoint

BREAKING CHANGE: /v1/users is replaced by /v2/users.
Clients must migrate before 3.0.
Refs: #512
```

### Rules

- **One commit = one coherent change.** No `wip`, `fix`, `update`, `it works`,
  `tmp`, `revert of my revert`.
- **No mass formatting commit** mixed with functional work (it makes the diff
  unreadable). If the repository is not formatted, make an isolated `style:`
  commit, referenced in `.git-blame-ignore-revs`.
- **No empty commit**, except `chore: trigger ci` with an explicit reason.
- Rebase / `commit --amend` your own branch to clean it up before the PR. Never
  rewrite the history of a shared branch or of `main`.
- A commit that fixes a bug cites the issue: `Closes #123` / `Refs: #123` in the
  footer.
- **Never commit an ignored file by force** (`git add -f` is forbidden without a
  written reason).

### Optional hook (local validation)

Place in `.git/hooks/commit-msg` (or via `pre-commit`/`lefthook` if already in
use):

```bash
#!/usr/bin/env sh
msg_file="$1"
pattern='^(feat|fix|docs|style|refactor|perf|test|build|ci|chore)(\([a-z0-9._/-]+\))?!?: .{3,}$'
head -n1 "$msg_file" | grep -Eq "$pattern" || {
  echo "Commit rejected: Conventional Commits format expected."
  echo "Example: feat(api): add pagination"
  exit 1
}
```

---

## 5. Quality before pushing

Before every `git push`, the strict minimum:

1. **Lint** — no new error.
2. **Formatting** — applied by the repository tool, not by hand.
3. **Tests** — the suite passes locally; every fixed bug ships with its test.
4. **Build** — it compiles.
5. **Review your own diff**: `git diff origin/main...` before opening the PR.

Rules:

- **Code follows the repository style**, not personal preference. If a tool
  formats, run it; do not argue.
- **A bug fix comes with a test that failed before.**
- **Never disable a test, a lint rule or a CI check** to get your PR through. If
  a test is genuinely wrong, fix it or open an issue and `skip` it explicitly,
  with a reference and an expiry date.
- An added dependency is justified in the PR (size, licence, maintenance,
  alternative).

---

## 6. Pull Requests

A PR is **a proposal**, not a diary. It must be understandable without opening
the code.

### Before opening

- Branch up to date with `main` (rebased).
- History cleaned (commits reviewed, `fix typo` absorbed).
- PR as **draft** until it is ready for review.
- **One subject per PR.** Refactor + feature + reformatting = 3 PRs.

### Title

It often becomes the squash message, so it follows **Conventional Commits**:

```
feat(export): add CSV export for invoices (#412)
```

### Description

```markdown
## Context
Why this change? What problem does it solve? (link to the issue)

## What changes
- …
- …

## How to verify
Concrete steps to reproduce / test the behaviour.

## Points of attention
Design decisions, added debt, side effects, data migration.

## Screenshots / evidence
Before/after if the UI changes; logs or test output where relevant.

Closes #412
```

### Rules

- **Target size < 400 useful lines.** Beyond that, split (by logical commit or by
  layer).
- **No mixing** of functional and formatting/reformatting work in the same PR.
- **No dead, commented-out code or `TODO` without a ticket.** A `TODO` must cite
  an issue.
- **No `console.log`, debug output, credentials, or hard-coded local paths.**
- **A PR without a description or an explained manual test will be closed without
  review.**
- Answer comments by **resolving the conversation**, not by leaving it open.
- An abandoned PR is **closed** (it does not stay open for months).

---

## 7. Code review

### For the author

- Review your own diff **before** asking for a review.
- The necessary context is in the description; if a reviewer has to guess,
  complete it.
- Answer **every** comment: fixed, or explained (with agreement).
- Do not force-push **while a review is active**, unless warned in the PR
  (otherwise comments are lost).
- **Do not merge your own PR** without approval, even with green CI.

### For the reviewer

- Review **within the day** where possible (waiting is the first cost of a
  review).
- Comment on **the code, never the person**; propose, do not impose.
  - Blocking: `[blocking]`, `[security]`, `[bug]` → must be resolved.
  - Non-blocking: `[nit]`, `[suggestion]`, `[question]` → the author decides.
- Check **behaviour**, not style (style is the linter's job).
- Look for: edge cases, unhandled errors, resource leaks, data access,
  concurrency, secrets, missing tests on the critical path.
- **Approve explicitly** or request changes — no ambiguous "LGTM" without reading.

### What blocks a merge

- Red CI, conflict, branch behind `main`.
- Missing approval.
- Unresolved `[blocking]` comment.
- A capability without tests or a verification procedure.
- A secret, generated file or large binary added.

---

## 8. Merging

Default strategy, unless the repository `README.md` states otherwise:

| Case                              | Method                            |
| --------------------------------- | --------------------------------- |
| Regular PR                        | **Squash and merge**              |
| Already clean branch, 1 commit    | **Rebase and merge** (fast-forward) |
| Urgent production fix             | Merge commit, recorded in the PR  |

Rules:

- The source branch is **deleted** after merging.
- Do not merge a PR you did not author unless the author asks for it.
- The squash message reuses the **PR title** plus the useful body, not the raw
  list of intermediate commits.
- After merging, **update your local copy** (`git switch main && git pull --rebase`).

---

## 9. Versions and releases

- **SemVer**: `MAJOR.MINOR.PATCH`.
  - `MAJOR`: breaking compatibility (requires a `BREAKING CHANGE`).
  - `MINOR`: compatible addition (`feat`).
  - `PATCH`: compatible fix (`fix`, `perf`).
- A version is an **annotated tag** (`v1.4.0`) on `main`, never on a branch.
- `CHANGELOG.md` is **generated from the commits** (Conventional Commits) or kept
  by hand but in the same commit as the version bump. No "miscellaneous" section.
- A release is **reproducible**: the tag must allow rebuilding the shipped
  artifact.
- No `v2-final`, `v2-final-really`, `v2-ok`. A fix is a new version.

---

## 10. Repository hygiene

- `.gitignore` up to date **from the first commit** (dependencies, build output,
  IDE, OS, local secrets).
- **No generated file** under version control (unless an explicit, documented
  decision: lockfiles, documentation artifacts).
- **No large binary.** Above ~5 MB, use Git LFS or external storage.
- No personal IDE files (`.idea/`, `.vscode/` unless a shared configuration is
  deliberately committed).
- No `Thumbs.db`, `.DS_Store`, `node_modules/`, `dist/`, `.env`.
- No duplicate or poorly named files (`copy`, `test2`, `old`, `backup`, `final`).
- Moved files use `git mv` (history follows).
- A repository holds **one project**. No catch-all.

---

## 11. Security

- **Never a plaintext secret**: password, token, private key, connection string,
  certificate. A pushed secret is **compromised**: revoke it; deleting it from the
  last commit is not enough (it stays in history).
- Use environment variables, a local ignored `.env`, and a documented
  `.env.example` with no real value.
- Do not log personal data, tokens or credentials.
- Report a vulnerability **privately** to the maintainer (see `SECURITY.md` when
  present), never in a public issue.
- Dependencies follow security fixes; a known critical vulnerability blocks the
  release.

---

## 12. Issues and proposals

A good issue contains:

- **Context**: what was expected, what actually happens.
- **Reproduction**: minimal steps, version/commit, environment.
- **Evidence**: full error message, logs, screenshot.
- **Impact**: blocking? data loss? cosmetic?

A feature proposal describes **the need and the use case**, not only the desired
technical solution. Discuss the *what* before the *how*.

An issue is not a chat: important decisions are summarised at the end of the
thread.

---

## 13. Checklist before opening a PR

```markdown
- [ ] The branch starts from an up-to-date `main` and is rebased
- [ ] Commits follow Conventional Commits and are atomic
- [ ] Lint, formatting, tests and build pass locally
- [ ] I reviewed my own diff (`git diff origin/main...`)
- [ ] A single subject is addressed
- [ ] The description explains context, change and how to verify
- [ ] A test covers the new behaviour / the fixed bug
- [ ] No secret, generated file, heavy binary or debug code
- [ ] Documentation is up to date (README, API docs, CHANGELOG when versioning)
- [ ] Any TODO cites an issue
```

---

## 14. Adapting this document to a repository

This file is meant to be copied as-is into any repository. To adapt it, change
only these elements, in its `README.md` (not here):

| Element                     | Where to define it                    |
| --------------------------- | ------------------------------------- |
| Install/test/lint commands  | Repository `README.md`                |
| Commit scope list           | `README.md`, "Conventions" section     |
| Merge strategy              | GitHub repository settings + README note |
| Default branch              | `main` (unless a migration is in progress) |
| PR template                 | `.github/pull_request_template.md`    |
| Issue templates             | `.github/ISSUE_TEMPLATE/`             |
| Security policy             | `SECURITY.md`                         |
| Licence                     | `LICENSE`                             |

This document takes precedence over individual habits; where it disagrees with a
repository setting, the repository setting wins — but it must then be written
down.

---

## Licence and contributions

By submitting a contribution you agree that it is distributed under the
repository licence (see `LICENSE`) and you certify that you are its author or
have the right to submit it.
