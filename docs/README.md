# OsWL documentation

**[English](en/Home.md)** | [한국어](ko/Home.md) | [日本語](ja/Home.md)

English is the default documentation entry point. Start with the [English documentation home](en/Home.md), or choose a language above.

- [Getting started](en/Getting-Started.md)
- [User guide](en/User-Guide.md)
- [Production deployment](en/Production-Deployment-Checklist.md)
- [Backup and restore](en/Backup-And-Restore.md)
- [API reference](en/API-Reference.md)
- [Developer onboarding (Korean)](ko/Developer-Onboarding.md)
- [Architecture and optimization (Korean)](ko/Architecture-Optimization.md)
- [Roadmap final audit (Korean)](ko/Roadmap-Final-Audit.md)
- [UI states checklist (English)](en/Ui-States-Checklist.md)

- [Browser security alerts (English)](en/Browser-Security-Alerts.md)

- [Container image inspection (English)](en/Container-Image-Inspection.md)

- [Local cluster rehearsal (English)](en/Local-Cluster-Rehearsal.md)

## Where files belong

Document bodies live in `en/`, `ko/`, or `ja/` according to their actual language. Keep matching filenames for translations and prefer links to pages in the same language. Some developer references currently exist in only one language; link to that version with its language identified instead of creating empty translations.

[Deployment files](../deploy/README.md) contain Docker build/run configuration and the Grafana dashboard. [Scripts](../scripts/README.md) contain executable tools. The [landing site](../landing/index.html) is published separately through GitHub Pages.

## GitHub Wiki publishing

The [English Wiki](https://github.com/SalkCoding/Oswl/wiki) is generated from `en/` on pushes to `main`. Edit the source documents here; the sync mirrors its generated input and removes pages no longer present in that input.

The [preparation script](../.github/scripts/prepare-wiki.py) writes ignored output to `build/wiki/`. It converts links between English pages to Wiki links and links to other repository files to GitHub URLs. `Home.md` and `_Sidebar.md` retain their Wiki names. Korean and Japanese documents remain available through repository links from the Wiki.

Run `python .github/scripts/prepare-wiki.py SalkCoding/Oswl` from the repository root to inspect the publishing output without publishing anything. The workflow's manual dispatch defaults to a dry run; publishing requires disabling its `dry_run` input. This does not change the landing site's Pages deployment.
