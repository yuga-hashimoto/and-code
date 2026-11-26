# Architecture decision: experimental distribution boundary

Status: **investigation only; not an approved release architecture**. Preserve current local
execution during investigation; never claim PRoot is a VM exemption.

| Candidate | Policy / functionality / costs |
|---|---|
| A: current external downloads | Highest existing feature preservation, smallest bundle, low change; downloaded native code and unrestricted package installation are a serious Play risk. Not submission-ready. |
| B: package executable payload in Play artifact | Reduces external executable downloads; large AAB, build/licensing/SBOM/update costs. Package manager and later CLI binaries still require resolution. Needs measured size, licenses and Google review. |
| C: Play feature/asset delivery | Potential Google-distributed components; eligibility, executability, size, updates and packaging must be verified. Does not authorize third-party downloaded native code. |
| D: user-managed development environment | Good UX for developers; user consent alone does not override policy. A terminal or PRoot is not proof of interpreter exception. |
| E: restricted local Play variant | Can reduce policy exposure, but removes some agents/package functionality. Identify concrete limits and consent, do not hide capability from reviewers. |
| F: remote-only | Least local feature parity; do not choose merely for convenience. |

The `play` flavor is a build and investigation scaffold implementing A, **not the final design**.
Use B/C as the primary feasibility experiments and obtain explicit policy interpretation for native
ELFs, package installation, and PRoot. If that fails, evaluate E before F. Do not submit A unchanged.

## Identity and signing

The scaffold uses `com.yugahashimoto.andcode.play`, retaining minSdk 26 and targetSdk 36 only for
Play. A separate ID prevents in-place updates and data migration across distributions; users can
install both side by side. Data transfer needs an explicit, tested export/import workflow. Existing
GitHub and official F-Droid signing certificate fingerprints, Play application ID availability,
upgrade expectations, and app-signing key custody have not been verified. Same ID would require
compatible signer lineage and coordinated version codes, and could replace features across stores.
Existing `release` signing config is shared if its environment variables are provided; do not use
production credentials for experimental Play builds. Decide and implement separate Play upload-key
configuration before release. No key or Play Console operation was performed.
