package com.bromax.bromaxbattle.weapon;

public enum WeaponCategory {
    SHORTSWORD, LONGSWORD, BROADSWORD, SABRE, RAPIER,
    GREATSWORD, ZWEIHANDER, KATANA, FLAMBERGE, EXECUTIONER_SWORD, FALCHION,
    SWORD,

    DAGGER, HUNTERS_KNIFE, SAI,

    SPEAR, TRIDENT, HALBERD, GLAIVE, LANCE,
    POLEARM,

    MACE, WARHAMMER, HAMMER, FLAIL, GREATHAMMER,

    HANDAXE, BATTLEAXE, CLEAVER, LUMBERAXE,
    AXE,

    TWINBLADES, SCYTHE, SICKLE, GAUNTLETS, CLAW, NUNCHAKU, QUARTERSTAFF, WHIP, STAFF,

    SHORTBOW, LONGBOW, CROSSBOW, BOW;

    public boolean isTwoHanded() {
        return this == GREATSWORD || this == ZWEIHANDER || this == FLAMBERGE
            || this == EXECUTIONER_SWORD
            || this == BATTLEAXE || this == WARHAMMER || this == LUMBERAXE || this == GREATHAMMER
            || this == POLEARM || this == HALBERD || this == GLAIVE
            || this == LANCE || this == TRIDENT || this == SPEAR
            || this == TWINBLADES
            || this == SCYTHE
            || this == QUARTERSTAFF
            || this == LONGBOW || this == BOW || this == CROSSBOW;
    }

    public boolean isRanged() {
        return this == SHORTBOW || this == LONGBOW || this == BOW || this == CROSSBOW;
    }
}
