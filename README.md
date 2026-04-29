# once-ai

Deployment configuration for [bigconfig.ai](https://www.bigconfig.ai), built on top of [`io.github.amiorin/once`](https://github.com/amiorin/once).

This repo is a thin [Babashka](https://babashka.org/) shim: `bb.edn` pins the `once` library to a specific git sha and passes a single params map describing the providers and applications to deploy.

## Usage

```sh
bb ai create   # render and provision
bb ai delete   # tear down
```

`bb tasks` lists available tasks.

## Stack

- **Compute**: Oracle Cloud — Ampere A1.Flex VM in `eu-frankfurt-1`
- **DNS**: Cloudflare
- **SMTP**: Resend
- **Terraform state**: S3 (`eu-west-1`)
- **Applications** (containers, see `bb.edn`):
  - `ghcr.io/amiorin/once-bigconfig` → `www.bigconfig.ai`
  - `ghcr.io/amiorin/once-caddy-redirect` → `bigconfig.ai`

## Configuration

All deployment parameters live in the params map in [`bb.edn`](./bb.edn). To change region, instance shape, applications, or domain, edit that map and re-run `bb ai create`. To pick up upstream `once` changes, bump `:git/sha`.

For local development against a checkout of the `once` repo, swap the active key in `bb.edn` from `:git/sha` to `:local/root`.

## Secrets

Provider credentials are supplied via `BC_PAR_*` environment variables loaded from `.envrc` (gitignored, managed with [direnv](https://direnv.net/)). Required tokens cover Hetzner, Cloudflare, DigitalOcean, Resend, AWS (Litestream), and PocketBase superuser credentials.
