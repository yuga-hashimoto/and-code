# Feature matrix

| Capability | GitHub/F-Droid | Experimental Play |
|---|---|---|
| Local Linux, agents, terminal, Git, packages, MCP, preview, remote connection | Existing implementation | Shared code remains present; functionality and Play policy unverified |
| API target / minSdk | 35 / 26 | 36 / 26 |
| Package | `com.yugahashimoto.andcode` | `com.yugahashimoto.andcode.play`; no automatic data migration |
| Firebase | GitHub: opt-in; F-Droid: absent | no-op diagnostics and no Firebase dependencies intended; verify final bundle |
| All-files access, wake word, schedules | Existing manifest | inherited, not yet policy-reviewed |
| In-app AI response reporting | not established | not implemented |

Presence in source is not a successful device test or policy approval.
