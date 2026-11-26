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

The `play` flavor is an investigation scaffold combining A and B, **not the final design**. It
ships pinned Alpine minirootfs and OpenCode archives for each ABI as Play-delivered assets,
verifies SHA-256 before use, and limits OpenCode updates to the app-bundled version. Both ABI
archives currently ship to every device, adding approximately 160 MiB; per-ABI delivery remains an
optimization. The Debian rootfs, other native CLI binaries and package-manager packages still come
from outside Play. Their distribution is unresolved.
Continue B/C feasibility experiments and seek a policy interpretation for native ELFs, package
installation, and PRoot. If those approaches fail, evaluate E before F. Do not submit this scaffold.

## Identity and signing

The scaffold uses `com.yugahashimoto.andcode.play`, retaining minSdk 26 and targetSdk 36 only for
Play. A separate ID prevents in-place updates and data migration across distributions; users can
install both side by side. The Play manifest assigns local OpenCode port 4107 rather than 4097 so both distributions can run side by side; arbitrary port collisions and live coexistence still require testing. Data transfer needs an explicit, tested export/import workflow. Existing
GitHub and official F-Droid signing certificate fingerprints, Play application ID availability,
upgrade expectations, and app-signing key custody have not been verified. Same ID would require
compatible signer lineage and coordinated version codes, and could replace features across stores.
Play signing uses separate `AND_CODE_PLAY_*` credentials, and remains unsigned without them even
when the existing distribution signing credentials are present. Final upload-key and app-signing
key custody still needs an explicit release decision. No key or Play Console operation was performed.
