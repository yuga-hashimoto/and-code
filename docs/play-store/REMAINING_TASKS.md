# Release blockers and remaining work

Do not submit the experimental Play flavor.

1. Resolve downloaded ELF/rootfs/package-manager policy with Google using exact flows. The Play
   build now bundles Alpine, its mandatory tool packages, and OpenCode, but optional developer
   tools, Debian, other CLI binaries and user-installed packages still need a policy-compatible
   solution without silently downgrading the local experience.
2. The optional private, accountless report receiver and client are implemented locally but are not
   deployed or configured. Establish a staffed moderation process and service operations, validate
   the actual deployed endpoint end to end and with Google, and evaluate restricted-content prevention.
   The unconfigured build offers a clearly labeled public GitHub issue requiring an account.
3. Complete the source and notice obligations for GPL and other packages in bundled Alpine and
   transitive components in the bundled OpenCode binaries before distribution. The exact Alpine
   package, license and recipe inventory is available in `ALPINE_PACKAGE_INVENTORY.md`. The source
   generator can recreate the bundled Alpine recipe sources locally, but this archive is neither
   a complete corresponding-source offer nor hosted for recipients.
4. Audit and implement permission minimization, especially `MANAGE_EXTERNAL_STORAGE` and SAF;
   verify foreground, microphone, boot, exact-alarm and denial behavior on API 36.
5. Verify each ELF and APK/AAB alignment, including PRoot, talloc, shmem, Vosk and JNA; test both
   ABIs and downloaded Linux binaries on 16 KB devices.
6. Validate runtime, auth, provider terms and trademarks; verify Antigravity provenance for each delivered version/ABI (both official 1.5.18 arm64 and x86_64
   archives matched their GitHub digests), redistribution rights and authentication terms. Audit browser automation, secrets and all data transfers.
7. Verify GitHub/F-Droid signing certificates and choose final Play ID and upload-key custody. The separate upload-key
   configuration is implemented, but no production key has been selected. Implement explicit export/import and ensure no unintentional cross-store upgrades.
8. Resolve the 32-bit loader alignment findings and make the ELF investigation a strict PR gate once the loader findings are resolved; run regression and
   end-to-end device tests in TEST_REPORT.md. AGP 8.11.1 supports API 36; the impact of its upgrade
   on source-built F-Droid reproducibility still needs verification.
9. Update Play-specific privacy policy, Data safety and permissions declarations based on actual
   artifacts; fill REVIEW_GUIDE after the implementation is final.
10. Complete required independent pre-PR review, CI, and PR workflow. Play Console registration,
   review submission and release are explicitly outside this task.
11. Configure the existing root `detekt` task to analyze app sources without breaking existing
    distribution builds. It currently reports `NO-SOURCE`; Android Lint does analyze Play code.
