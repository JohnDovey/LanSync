# LanSync — AI assistant notes

## Environment

This project lives on the `/Volumes/JohnDovey` external drive, alongside sibling Go
projects (WalkieTalkie, ServiceMonitor, QuakeMesh, MeshChat, wShare). See
`.cursor/rules/dev-environment.mdc` and `.cursor/rules/volume-storage.mdc`.

## Go module hygiene / Dependabot (standing rule)

**Lesson from WalkieTalkie (2026-07):** a multi-module repo with `go.work` still showed
~22 Dependabot alerts even though the workspace build was clean, because Dependabot scans
each `go.mod`/`go.sum` independently and a sibling module still pinned vulnerable
`x/crypto`/`x/net`. If this project grows a `go.work` / multiple modules, check *every*
`go.mod` with `GOWORK=off`, not just the workspace view. See
`.cursor/rules/dependabot-go-modules.mdc`.

## Deployment via ServiceMonitor

LanSync is intended to run as a managed service under the sibling `ServiceMonitor` project
(`/Volumes/JohnDovey/Projects/ServiceMonitor`), following the same pattern as WalkieTalkie,
QuakeMesh, MeshChat, and wShare. See `.cursor/rules/servicemonitor-deploy.mdc`.

## Related project notes

See `.cursor/rules/` for volume storage, versioning, dev environment, Dependabot hygiene,
and ServiceMonitor deployment conventions.
