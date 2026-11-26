# Release blockers and remaining work

Do not submit the experimental Play flavor.

1. Resolve downloaded ELF/rootfs/package-manager policy with Google using exact flows; implement
   an acceptable Play-delivered alternative without silently downgrading the local experience.
2. Build an in-app reporting workflow to the developer with explicit, minimal user-approved data,
   reliable delivery and moderation process; evaluate restricted-content prevention.
3. Audit and implement permission minimization, especially `MANAGE_EXTERNAL_STORAGE` and SAF;
   verify foreground, microphone, boot, exact-alarm and denial behavior on API 36.
4. Verify each ELF and APK/AAB alignment, including PRoot, talloc, shmem, Vosk and JNA; test both
   ABIs and downloaded Linux binaries on 16 KB devices.
5. Validate runtime, auth, provider terms and trademarks; independently establish ownership of the
   Antigravity download source. Audit browser automation, secrets and all data transfers.
6. Verify GitHub/F-Droid signing certificates and choose final Play ID and dedicated upload-key
   config. Implement explicit export/import and ensure no unintentional cross-store upgrades.
7. Fix the currently failing 32-bit loader alignment check and make the manually dispatched CI job a PR gate; run regression and
   end-to-end device tests in TEST_REPORT.md. Fix AGP/API 36 compatibility warning.
8. Update Play-specific privacy policy, Data safety and permissions declarations based on actual
   artifacts; fill REVIEW_GUIDE after the implementation is final.
9. Complete required independent pre-PR review, CI, and PR workflow. Play Console registration,
   review submission and release are explicitly outside this task.
