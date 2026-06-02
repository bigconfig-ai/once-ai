# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

This is **not** a library leaf — it is a concrete BigConfig **`once` package instance** that deploys the live `bigconfig.ai` infrastructure. It was bootstrapped by the `bc-pkg` launcher: the directory pins `bigconfig-ai/once` at a git SHA and forwards commands to it. The package definition lives in the single `run` file; the only other source is one small local plugin under `src/`. Everything else is pinned-dependency metadata or generated output.

Its own git repo (separate from the parent `bigconfig/` workspace and from the `once` library). Stay on `main`; do not commit unless asked.

## Files

- `run` — the package. A Babashka script (`#!/usr/bin/env bb`) that requires the local `once-ai.plugin`, defines `default-profile` (`::render/profile "ai"`: all `::workflow/params` plus the `:once` application list), and calls `(cli/main* *command-line-args* default-profile)`. **This is the main file you edit to change the deployment.**
- `src/io/github/bigconfig_ai/once_ai/plugin.clj` — local BigConfig plugin (see below). Loaded by `run` via its `:paths ["src"]` entry in `deps.edn`/`bb.edn`. The only hand-written code other than `run`.
- `deps.edn` / `bb.edn` — pin `io.github.bigconfig-ai/once` (explicit `:git/url` + `:git/sha`) and carry `:bigconfig/*` launcher metadata (`repo`, `ref clojure`, `sha`, `language clojure`, `run`). Bump the SHA in **both** files together to take a new `once` version.
- `.envrc` — secrets as `BC_PAR_*` exports (Cloudflare/Resend tokens, Litestream keys, Google OAuth, superuser password), loaded via direnv. Gitignored. Real credentials live here only.
- `.envrc.example` — committed template listing the required `BC_PAR_*` names with `...` placeholders. The one dotfile besides `.gitignore` that is tracked. Update it when the profile starts/stops needing a secret.
- `plans/` — scratch task notes (e.g. `01.md`), not part of the build.
- `.dist/ai-378cc184/` — **generated** rendered artifact (the `once`/`tofu`/`ansible` tool tree); the directory name is `<profile>-<hash>`. Never edit by hand; produced by running the package. `.terraform/` state under it is also generated. (A stale `.dist/website-378cc184/` from before the rename may linger — it is not the current artifact.)

`.gitignore` ignores every dotfile except `.gitignore` and `.envrc.example`, so `.envrc`, `.dist/`, `.cpcache/`, `.clj-kondo/`, `.lsp/` are all untracked.

## Running

Invoke the package through its `run` script (it is the launcher-designated entry point):

```
./run <subcommand>        # e.g. package create | package delete | package build
```

The `once` CLI behavior (six-stage create pipeline `tofu → tofu-smtp → tofu-dns → tofu-smtp-post → ansible-local → ansible`, `delete` reversing the four Tofu stages, `package build` rendering to `.dist/`) is documented in the parent `bigconfig/CLAUDE.md` and the `once` library's own `CLAUDE.md`. Behavior comes entirely from the pinned `once` dependency plus the local plugin, not from anything else in this repo.

`direnv allow` first so the `BC_PAR_*` secrets are in the environment; missing ones will surface as render/apply failures, not crashes.

## The local `once-ai` plugin

`src/.../plugin.clj` wraps the `:io.github.bigconfig-ai.once.tools/ansible-local` step via `big-config`'s pluggable step registry (`register-handle-step`, falling back to mutating the `handle-step` multimethod). After `ansible-local` succeeds (`::bc/exit` is 0), it pushes the deployed VM's IP to the **`SERVER_IP` GitHub org secret** under the `bigconfig-ai` org using the `gh` CLI:

- Reads the IP from `(get-in opts [::workflow/params :ip])`. **Refuses** to update if the IP is blank or a known fallback (`192.168.0.1`) — this guards against overwriting the live secret with a placeholder.
- Preserves the secret's existing visibility scope: reads current visibility via `gh api`; if `selected`, re-resolves and re-passes the selected repo list so the scope isn't widened. A missing secret (HTTP 404) defaults to `all`.
- Requires a working `gh` auth context with org-secret write access.

This is the one piece of deployment *logic* that lives in this repo rather than in the upstream `once` library.

## The `ai` profile (what this deploys)

- **Compute**: OCI (`VM.Standard.A1.Flex`, 1 OCPU / 6 GB) in `eu-frankfurt-1`.
- **DNS**: Cloudflare. **SMTP**: Resend. **Remote state backend**: S3 (`tf-state-251213589273-eu-west-1`).
- **Apps** (`:once {:applications [...]}`) deployed behind hosts under `bigconfig.ai`: `www` (once-bigconfig), apex redirect (once-caddy-redirect), `forms`, and `marketplace` (PocketBase + Litestream to S3, Google OAuth).

Params follow the standard BigConfig flow: profile params here → `BC_PAR_*` env overrides (uppercase, `-`/`.` → `_`) → values pulled from earlier-stage Tofu outputs. Template placeholders like `<{ litestream-access-key-id }>` in the `:env` lists are filled from the matching `BC_PAR_*` value at render time.

## Conventions

- Keep param keys kebab-case (they map to template variable names); do not convert to snake/camel case.
- `prevent_destroy = true` is the default on compute. To tear down: `BC_PAR_COMPUTE_PREVENT_DESTROY=false ./run package delete`.
- To change the deployment, edit `default-profile` in `run`. To change deployment *logic*, either edit the local plugin (for this-package-only behavior) or change the upstream `once` library and re-pin the SHA in `deps.edn` + `bb.edn`.
