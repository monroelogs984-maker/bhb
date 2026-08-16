package com.bromax.bromaxbattle.weapon;

public enum WeaponCategory {
    // ── Swords ──────────────────────────────────────────────────────────────
    SHORTSWORD,        // close-quarters, fast defensive slashes
    LONGSWORD,         // balanced all-rounder
    BROADSWORD,        // wide heavy blade, chopping focus
    SABRE,             // curved, flowing horizontal arcs
    RAPIER,            // near-pure thrust, precision extension
    GREATSWORD,        // massive two-handed, full-body wind-up
    ZWEIHANDER,        // battlefield two-handed sword, wide crowd-clearing sweeps
    KATANA,            // fast precise cuts, Japanese aesthetic
    FLAMBERGE,         // wavy-bladed two-handed sword, wide dramatic sweeps
    EXECUTIONER_SWORD, // massive ceremonial overhead weapon, slow and devastating
    FALCHION,          // single-edge curved chopping sword, brutal efficiency
    SWORD,             // generic fallback for unrecognised swords

    // ── Knives ──────────────────────────────────────────────────────────────
    DAGGER,       // mid-range, stab + slash mix
    HUNTERS_KNIFE,// heavier single-edge, slash-dominant

    // ── Polearms ────────────────────────────────────────────────────────────
    SPEAR,        // long reach thrust
    TRIDENT,      // thrust + wide sweep combo, more offensive than spear
    HALBERD,      // axe-on-pole: chop + sweep + thrust
    GLAIVE,       // pole blade, fast flowing sweeps
    LANCE,        // extreme reach, single heavy charge thrust
    POLEARM,      // generic fallback for unrecognised polearms

    // ── Blunt ───────────────────────────────────────────────────────────────
    MACE,         // medium blunt, side strikes
    WARHAMMER,    // two-handed crushing overhead
    HAMMER,       // one-handed blunt, direct downward drive
    FLAIL,        // looping delayed arc, unpredictable

    // ── Axes ────────────────────────────────────────────────────────────────
    HANDAXE,      // fast one-handed chops
    BATTLEAXE,    // two-handed deliberate chops
    CLEAVER,      // wide heavy blade, brutal chops
    LUMBERAXE,    // logging tool as weapon — wildly variable
    AXE,          // generic fallback for unrecognised axes

    // ── Other melee ─────────────────────────────────────────────────────────
    SCYTHE,       // wide reaping sweeps
    SICKLE,       // one-handed fast curved reaping blade
    GAUNTLETS,    // fist weapon — mostly default, explosive rares
    CLAW,         // arm-mounted multi-strike blades, fast close range
    NUNCHAKU,     // fast chain-linked clubs, fluid circular motion
    QUARTERSTAFF, // pure athletic two-handed blunt pole, no magic connotation
    WHIP,         // long reach flexible weapon, cracking delayed arc
    STAFF,        // pole weapon/magic focus

    // ── Ranged ──────────────────────────────────────────────────────────────
    SHORTBOW,
    LONGBOW,
    CROSSBOW,
    BOW;          // generic fallback for unrecognised bows

    public boolean isTwoHanded() {
        return this == GREATSWORD      || this == ZWEIHANDER   || this == FLAMBERGE
            || this == EXECUTIONER_SWORD
            || this == BATTLEAXE       || this == WARHAMMER    || this == LUMBERAXE
            || this == POLEARM         || this == HALBERD      || this == GLAIVE
            || this == LANCE           || this == TRIDENT      || this == SPEAR
            || this == SCYTHE
            || this == QUARTERSTAFF
            || this == LONGBOW         || this == BOW          || this == CROSSBOW;
    }

    public boolean isRanged() {
        return this == SHORTBOW || this == LONGBOW || this == BOW || this == CROSSBOW;
    }
}
