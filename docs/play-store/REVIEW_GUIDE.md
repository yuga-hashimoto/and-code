# Play reviewer guide — DRAFT, not ready to submit

AndCode is an independent Android coding client. It can connect to a user-configured OpenCode
server or launch local Linux userlands through a PRoot-based runtime. The Play build includes pinned Alpine rootfs and OpenCode archives for both supported ABIs and
verifies their hashes before use. OpenCode updates are delivered through updated Play app builds. Local mode currently obtains additional Linux packages, Debian files and agent executables
over the network, verifies selected downloads and launches them inside the app's runtime. PRoot is not represented here as a virtual machine or a policy exemption.
The current download design requires policy resolution before submission.

The app provides project editing, Git, terminal, agent chat, optional browser preview, schedules,
voice and remote connection. It is not affiliated with the named AI providers. Each provider sets
its own authentication, terms, data use and billing. No review credentials are included here.

Before review, the maintainer must document final user-visible setup steps, exact downloads and
sizes, every permission and denial fallback, a test account and credentials delivered privately
through Play Console if required, and an accessible in-app content-reporting/moderation process. The current experimental report
creates a public GitHub issue using a connected GitHub account and requires a manually entered
excerpt; it does not include the conversation automatically. Reporting without a GitHub account and
Google review of this approach remain outstanding.

For a demo video on a clean test device: record launch, onboarding, the user-approved runtime setup,
agent authentication using a dedicated test account without exposing secrets, a harmless chat and
file edit, terminal and browser preview, permission grant/denial, and report submission. Show any
special-use service notification. Do not record or provide real credentials or personal data.
