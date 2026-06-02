# once-ai

A concrete BigConfig **`once` package instance** that deploys the live
[`bigconfig.ai`](https://bigconfig.ai) infrastructure.

This is **not** a library — it is a deployment. The directory was bootstrapped
by the `bc-pkg` launcher: it pins `bigconfig-ai/once` at a git SHA and forwards
commands to it. The whole package is a single Babashka `run` script plus one
small local plugin.

## What it deploys

The `ai` profile provisions:

- **Compute** — OCI `VM.Standard.A1.Flex` (1 OCPU / 6 GB) in `eu-frankfurt-1`.
- **DNS** — Cloudflare.
- **SMTP** — Resend.
- **Remote state backend** — S3 (`tf-state-251213589273-eu-west-1`).

Apps served behind hosts under `bigconfig.ai`:

| Host | Image | Notes |
|---|---|---|
| `www.bigconfig.ai` | `once-bigconfig` | Main site |
| `bigconfig.ai` | `once-caddy-redirect` | Apex → `www` redirect |
| `forms.bigconfig.ai` | `once-forms` | Form handler |
| `marketplace.bigconfig.ai` | `once-bigconfig-marketplace` | PocketBase + Litestream to S3, Google OAuth |

## Prerequisites

- [Babashka](https://babashka.org/) (`bb`) — the `run` script is `#!/usr/bin/env bb`.
- [`direnv`](https://direnv.net/) — loads the `BC_PAR_*` secrets from `.envrc`.
- The [`gh`](https://cli.github.com/) CLI, authenticated with write access to
  the `bigconfig-ai` org secrets (used by the local plugin to sync `SERVER_IP`).
- OCI, Cloudflare, Resend, AWS (S3) credentials as required by the providers.

## Setup

1. Copy the secrets template and fill in real values:

   ```sh
   cp .envrc.example .envrc
   # edit .envrc
   direnv allow
   ```

   Required secrets (see `.envrc.example`):

   ```
   BC_PAR_CLOUDFLARE_API_TOKEN
   BC_PAR_RESEND_API_KEY
   BC_PAR_RESEND_PASSWORD
   BC_PAR_LITESTREAM_ACCESS_KEY_ID
   BC_PAR_LITESTREAM_SECRET_ACCESS_KEY
   BC_PAR_SUPERUSER_PASSWORD
   BC_PAR_GOOGLE_CLIENT_ID
   BC_PAR_GOOGLE_CLIENT_SECRET
   ```

   Missing secrets surface as render/apply failures, not crashes.

## Usage

Run the package through its `run` script:

```sh
./run package build      # render the artifact into .dist/<profile>-<hash>/
./run package create     # provision + configure (six-stage pipeline)
./run package delete     # tear down the four Tofu stages (reversed)
```

The `create` pipeline runs six stages:

```
tofu → tofu-smtp → tofu-dns → tofu-smtp-post → ansible-local → ansible
```

`delete` reverses the four Tofu stages. Compute resources default to
`prevent_destroy = true`; to tear one down:

```sh
BC_PAR_COMPUTE_PREVENT_DESTROY=false ./run package delete
```

## Files

| File | Purpose |
|---|---|
| `run` | The package. Babashka script defining `default-profile` and calling `cli/main*`. **Edit this to change the deployment.** |
| `src/io/github/bigconfig_ai/once_ai/plugin.clj` | Local BigConfig plugin (see below). |
| `deps.edn` / `bb.edn` | Pin `io.github.bigconfig-ai/once` (`:git/url` + `:git/sha`) and carry `:bigconfig/*` launcher metadata. Bump the SHA in **both** together. |
| `.envrc` | Secrets as `BC_PAR_*` exports (gitignored). |
| `.envrc.example` | Committed template of required secret names. |
| `.dist/<profile>-<hash>/` | **Generated** rendered artifact. Never edit by hand. |
| `plans/` | Scratch task notes, not part of the build. |

## The local `once-ai` plugin

`src/.../plugin.clj` wraps the upstream `ansible-local` step via BigConfig's
pluggable step registry. After `ansible-local` succeeds, it pushes the deployed
VM's IP to the **`SERVER_IP` GitHub org secret** in the `bigconfig-ai` org via
the `gh` CLI. It:

- Reads the IP from the workflow params.
- **Refuses** to update if the IP is blank or a known fallback (`192.168.0.1`),
  guarding against overwriting the live secret with a placeholder.
- Preserves the secret's existing visibility scope (re-resolving the selected
  repo list when scope is `selected`; defaulting to `all` for a missing secret).

This is the one piece of deployment *logic* that lives in this repo rather than
in the upstream `once` library.

## Changing the deployment

- **Deployment config** (profile params, apps, hosts): edit `default-profile` in
  `run`.
- **Deployment logic** for this package only: edit the local plugin.
- **Upstream behavior**: change the `once` library and re-pin its SHA in both
  `deps.edn` and `bb.edn`.

Keep param keys **kebab-case** — they map to template variable names; do not
convert to snake/camel case.

## Notes

- This repo has its own git history, separate from the parent `bigconfig/`
  workspace and from the `once` library. Stay on `main`; do not commit unless
  asked.
- Behavior comes entirely from the pinned `once` dependency plus the local
  plugin — nothing else in this repo defines runtime behavior.
