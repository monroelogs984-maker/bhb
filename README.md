# BROMAX's Haphazard Battle (BHB)

Haphazard semi-realistic combat with smart weapon recognition and unique attack animations.

## Branches

| Branch | Minecraft | Loader | License |
|---|---|---|---|
| `1.21.1-neoforge` (default) | 1.21.1 | NeoForge | CC BY 4.0 |
| `1.20.1-forge` | 1.20.1 | Forge | CC BY 4.0 |
| `1.19.2-forge` | 1.19.2 | Forge | All Rights Reserved (dev branch) |
| `1.12.2-forge` | 1.12.2 | Forge | Unspecified |
| `1.21.1-neoforge-bc` | 1.21.1 | NeoForge | MIT — separate "bc" addon variant (package `com.bromax.bhbbc`), bundled here alongside the main mod rather than as its own repo |

The `1.12.2-forge` branch depends on **bromaxlib**, a separate shared animation library
(its own repo). See CLAUDE.md conventions on this machine for the mixin `@Shadow` + SRG-name
pattern this branch relies on.

Note: the `1.12.2-forge` branch's original working directory also contained a top-level
`assets/bettercombat/attack_animations/` folder of reference animation data from the
*Better Combat* mod (not BROMAX's own work) — excluded from this repo.
