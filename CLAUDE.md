# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

This is **not** a library leaf — it is a concrete BigConfig **`once` package instance** that deploys the live `bigconfig.website` infrastructure. It was bootstrapped by the `bc-pkg` launcher: the directory pins `bigconfig-ai/once` at a git SHA and forwards commands to it. There is almost no source here; the entire package definition lives in the single `run` file, and everything else is either pinned-dependency metadata or generated output.

Its own git repo (separate from the parent `bigconfig/` workspace and from the `once` library). Single commit so far. Stay on `main`; do not commit unless asked.

## Files

- `run` — the package. A Babashka script (`#!/usr/bin/env bb`) that defines `default-profile` (the `website` profile: all `::workflow/params` plus the `:once` application list) and calls `(cli/main* *command-line-args* default-profile)`. **This is the only file you edit to change the deployment.**
- `deps.edn` / `bb.edn` — pin `io.github.bigconfig-ai/once` to `:git/sha` and carry `:bigconfig/*` launcher metadata (`repo`, `ref clojure`, `sha`, `language clojure`, `run`). Bump the SHA in **both** files together to take a new `once` version.
- `.envrc` — secrets as `BC_PAR_*` exports (Cloudflare/Resend tokens, Litestream keys, Google OAuth, superuser password), loaded via direnv. Gitignored. Real credentials live here only.
- `plans/` — scratch task notes (e.g. `01.md`), not part of the build.
- `.dist/website-378cc184/` — **generated** rendered artifact (the `once`/`tofu`/`ansible` tool tree). Never edit by hand; it is produced by running the package. `.terraform/` state under it is also generated.

`.gitignore` ignores every dotfile except itself, so `.envrc`, `.dist/`, `.cpcache/`, `.clj-kondo/`, `.lsp/` are all untracked.

## Running

Invoke the package through its `run` script (it is the launcher-designated entry point):

```
./run <subcommand>        # e.g. package create | package delete | package build
```

The `once` CLI behavior (six-stage create pipeline `tofu → tofu-smtp → tofu-dns → tofu-smtp-post → ansible-local → ansible`, `delete` reversing the four Tofu stages, `package build` rendering to `.dist/`) is documented in the parent `bigconfig/CLAUDE.md` and the `once` library's own `CLAUDE.md`. Behavior comes entirely from the pinned `once` dependency, not from this repo.

`direnv allow` first so the `BC_PAR_*` secrets are in the environment; missing ones will surface as render/apply failures, not crashes.

## The `website` profile (what this deploys)

- **Compute**: OCI (`VM.Standard.A1.Flex`, 1 OCPU / 6 GB) in `eu-frankfurt-1`.
- **DNS**: Cloudflare. **SMTP**: Resend. **Remote state backend**: S3 (`tf-state-251213589273-eu-west-1`).
- **Apps** (`:once {:applications [...]}`) deployed behind hosts under `bigconfig.website`: `www` (once-bigconfig), apex redirect, `forms`, and `marketplace` (PocketBase + Litestream to S3, Google OAuth).

Params follow the standard BigConfig flow: profile params here → `BC_PAR_*` env overrides (uppercase, `-`/`.` → `_`) → values pulled from earlier-stage Tofu outputs. Template placeholders like `<{ litestream-access-key-id }>` in the `:env` lists are filled from the matching `BC_PAR_*` value at render time.

## Conventions

- Keep param keys kebab-case (they map to template variable names); do not convert to snake/camel case.
- `prevent_destroy = true` is the default on compute. To tear down: `BC_PAR_COMPUTE_PREVENT_DESTROY=false ./run package delete`.
- To change the deployment, edit `default-profile` in `run`. To change deployment *logic*, change the upstream `once` library and re-pin the SHA in `deps.edn` + `bb.edn`.
