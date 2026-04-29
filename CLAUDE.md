# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A thin configuration shim for [`io.github.amiorin/once`](https://github.com/amiorin/once). There is no application source here — `bb.edn` declares the upstream library as a git dep and invokes `package/once*` with the `"ai"` profile and a single map of provider/workflow params. The actual logic (rendering, packaging, deploying) lives in the `once` library; everything in this repo is parameters for that library.

## Commands

- `bb ai create` — render the package and provision the deployment.
- `bb ai delete` — tear it down.
- `bb tasks` — list tasks (also triggers checkout of the pinned `once` git sha into `~/.gitlibs`).

The single task `ai` is defined in `bb.edn`; there is no test suite, build step, or linter wired up locally (clj-kondo configs in `.clj-kondo/imports/` are for editor support only).

## Architecture

- **`bb.edn`** is the entire codebase. It pins `io.github.amiorin/once` to a specific git sha and passes one params map covering: SMTP (Resend), DNS (Cloudflare), Terraform state backend (S3 in `eu-west-1`), compute (Oracle Cloud Ampere A1.Flex in `eu-frankfurt-1`), and the deployable `:once` applications.
- **`:once {:applications [...]}`** lists the containers to run on the provisioned host. Currently `ghcr.io/amiorin/once-bigconfig` on `www.bigconfig.ai` and `ghcr.io/amiorin/once-caddy-redirect` on the apex `bigconfig.ai`.
- **`.dist/ai-<hash>/`** is generated output from `bb ai create` — the rendered package tree under `io/github/amiorin/once/...`. Do not edit by hand; re-render via the task.
- **`.envrc`** supplies `BC_PAR_*` secrets (Hetzner, Cloudflare, DigitalOcean, Resend, Litestream/S3, PocketBase superuser) consumed by the `once` workflow at runtime. Loaded automatically by direnv.

## Editing params

To change deployment configuration (region, shape, applications, domain), edit the params map in `bb.edn` and re-run `bb ai create`. To pick up upstream `once` changes, bump `:git/sha`. The commented `:local/root "../once/main"` is the convention for local dev against a checkout of the `once` repo — swap which key is active.

## Secrets warning

`.envrc` is gitignored but contains live API tokens for Hetzner, Cloudflare, DigitalOcean, Resend, and AWS. Do not echo, commit, or paste its contents. If asked to debug auth, refer to variables by name only.
