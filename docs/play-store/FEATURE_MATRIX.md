# Feature matrix

| Capability | GitHub/F-Droid | Experimental Play |
|---|---|---|
| Local Linux, agents, terminal, Git, packages, MCP, preview, remote connection | Existing implementation | Alpine, OpenCode and mandatory packages bundled and verified; optional tools, other agents and user-installed packages still downloaded. Functionality and Play policy unverified |
| API target / minSdk | 35 / 26 | 36 / 26 |
| Package | `com.yugahashimoto.andcode` | `com.yugahashimoto.andcode.play`; no automatic data migration |
| Local OpenCode port | 4097 | 4107; live coexistence unverified |
| Firebase | GitHub: opt-in; F-Droid: absent | no-op diagnostics and no Firebase dependencies intended; verify final bundle |
| All-files access, wake word, schedules | Existing manifest | inherited, not yet policy-reviewed |
| In-app AI response reporting | not available | Play supports a private accountless endpoint when configured, otherwise a clearly labeled public GitHub issue; the private service is not deployed or configured |

Presence in source is not a successful device test or policy approval.
