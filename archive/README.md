# Archived animations

Animation files no weapon category references (checked against every `weapon_attributes/*.json`
attack and idle on 2026-10-07). They sit outside `src/main/resources`, so they are not packed into
the jar. Mostly the pre-redo generic set (`sword_slow_*`, `sword_fast_*`, `knife_*`, `punch_*`, ...)
plus the unused new-style `polearm_default/heavy/light`, kept in case the POLEARM category moves
to them. Move a file back to `src/main/resources/assets/bromax_battle/animations/` and reference it
from a weapon_attributes file to use it again.
