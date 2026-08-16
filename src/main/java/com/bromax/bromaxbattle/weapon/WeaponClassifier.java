package com.bromax.bromaxbattle.weapon;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Multi-signal weapon classifier, Better Combat-inspired.
 *
 * Architecture: every applicable tier votes for a category. The category with the
 * most accumulated evidence wins. When multiple independent signals agree the result
 * is much more confident than any single signal alone — an item identified by both
 * OreDict and name patterns is more certain than one identified by only one.
 *
 * Tier 1 (1.00): Per-item JSON override          — WeaponRegistry, before this is called
 * Tier 2 (0.95): Vanilla Minecraft explicit map   — hardcoded, no math, no failure modes
 * Tier 3 (0.90): OreDictionary                   — cross-mod standard; short-circuits
 * Tier 4 (0.80): getToolClasses()                — explicit tool class strings from mods
 * Tier 5 (0.85): Registry name keywords          — set by the mod developer, reliable
 * Tier 6 (0.75): Item class + attribute speed    — ItemSword/ItemAxe + speed refinement
 * Tier 7 (0.70): Stats profile (speed + damage)  — catches items with no name signals
 * Tier 8 (0.65): Reach attribute                 — high attack range → polearm family
 * Tier 9 (0.65): Display name keywords           — last keyword pass
 * Tier10 (0.30): Attack damage heuristic         — absolute last resort
 *
 * After tiers 4-9 run, an agreement boost is applied: if two or more independent
 * signals land on the same category, the final confidence is raised.
 */
public class WeaponClassifier {

    // =========================================================================
    // Tier 2: Vanilla Minecraft explicit map
    // =========================================================================
    private static final Map<String, WeaponCategory> VANILLA = new HashMap<>();
    static {
        VANILLA.put("minecraft:wooden_sword",  WeaponCategory.SHORTSWORD);
        VANILLA.put("minecraft:stone_sword",   WeaponCategory.SHORTSWORD);
        VANILLA.put("minecraft:iron_sword",    WeaponCategory.SHORTSWORD);
        VANILLA.put("minecraft:golden_sword",  WeaponCategory.SHORTSWORD);
        VANILLA.put("minecraft:diamond_sword", WeaponCategory.SHORTSWORD);
        VANILLA.put("minecraft:wooden_axe",    WeaponCategory.HANDAXE);
        VANILLA.put("minecraft:stone_axe",     WeaponCategory.HANDAXE);
        VANILLA.put("minecraft:iron_axe",      WeaponCategory.HANDAXE);
        VANILLA.put("minecraft:golden_axe",    WeaponCategory.HANDAXE);
        VANILLA.put("minecraft:diamond_axe",   WeaponCategory.HANDAXE);
        VANILLA.put("minecraft:bow",           WeaponCategory.LONGBOW);
    }

    // =========================================================================
    // Tier 3: OreDictionary
    // =========================================================================
    private static final Map<String, WeaponCategory> ORE_DICT = new LinkedHashMap<>();
    static {
        ORE_DICT.put("weaponRapier",           WeaponCategory.RAPIER);
        ORE_DICT.put("weaponKatana",           WeaponCategory.KATANA);
        ORE_DICT.put("weaponZweihander",       WeaponCategory.ZWEIHANDER);
        ORE_DICT.put("weaponFlamberge",        WeaponCategory.FLAMBERGE);
        ORE_DICT.put("weaponExecutioner",      WeaponCategory.EXECUTIONER_SWORD);
        ORE_DICT.put("weaponFalchion",         WeaponCategory.FALCHION);
        ORE_DICT.put("weaponGreatsword",       WeaponCategory.GREATSWORD);
        ORE_DICT.put("weaponBroadsword",    WeaponCategory.BROADSWORD);
        ORE_DICT.put("weaponLongsword",     WeaponCategory.LONGSWORD);
        ORE_DICT.put("weaponShortsword",    WeaponCategory.SHORTSWORD);
        ORE_DICT.put("weaponSabre",         WeaponCategory.SABRE);
        ORE_DICT.put("weaponCutlass",       WeaponCategory.SABRE);
        ORE_DICT.put("weaponLance",         WeaponCategory.LANCE);
        ORE_DICT.put("weaponHalberd",       WeaponCategory.HALBERD);
        ORE_DICT.put("weaponGlaive",        WeaponCategory.GLAIVE);
        ORE_DICT.put("weaponVoulge",        WeaponCategory.GLAIVE);
        ORE_DICT.put("weaponBardiche",      WeaponCategory.HALBERD);
        ORE_DICT.put("weaponTrident",       WeaponCategory.TRIDENT);
        ORE_DICT.put("weaponSpear",         WeaponCategory.SPEAR);
        ORE_DICT.put("weaponPike",          WeaponCategory.SPEAR);
        ORE_DICT.put("weaponJavelin",       WeaponCategory.SPEAR);
        ORE_DICT.put("weaponNaginata",      WeaponCategory.GLAIVE);
        ORE_DICT.put("weaponScythe",        WeaponCategory.SCYTHE);
        ORE_DICT.put("weaponPolearm",       WeaponCategory.POLEARM);
        ORE_DICT.put("weaponDagger",        WeaponCategory.DAGGER);
        ORE_DICT.put("weaponStiletto",      WeaponCategory.DAGGER);
        ORE_DICT.put("weaponParryDagger",   WeaponCategory.DAGGER);
        ORE_DICT.put("weaponThrowingKnife", WeaponCategory.DAGGER);
        ORE_DICT.put("weaponKnife",         WeaponCategory.HUNTERS_KNIFE);
        ORE_DICT.put("weaponBoomerang",     WeaponCategory.HUNTERS_KNIFE);
        ORE_DICT.put("weaponWarhammer",     WeaponCategory.WARHAMMER);
        ORE_DICT.put("weaponHammer",        WeaponCategory.HAMMER);
        ORE_DICT.put("weaponFlail",         WeaponCategory.FLAIL);
        ORE_DICT.put("weaponMace",          WeaponCategory.MACE);
        ORE_DICT.put("weaponBattleaxe",     WeaponCategory.BATTLEAXE);
        ORE_DICT.put("weaponGreataxe",      WeaponCategory.BATTLEAXE);
        ORE_DICT.put("weaponLumberaxe",     WeaponCategory.LUMBERAXE);
        ORE_DICT.put("weaponCleaver",       WeaponCategory.CLEAVER);
        ORE_DICT.put("weaponThrowingAxe",   WeaponCategory.HANDAXE);
        ORE_DICT.put("weaponAxe",           WeaponCategory.HANDAXE);
        ORE_DICT.put("weaponSickle",         WeaponCategory.SICKLE);
        ORE_DICT.put("weaponClaw",           WeaponCategory.CLAW);
        ORE_DICT.put("weaponNunchaku",       WeaponCategory.NUNCHAKU);
        ORE_DICT.put("weaponQuarterstaff",   WeaponCategory.QUARTERSTAFF);
        ORE_DICT.put("weaponBattlestaff",    WeaponCategory.QUARTERSTAFF);
        ORE_DICT.put("weaponWhip",           WeaponCategory.WHIP);
        ORE_DICT.put("weaponCaestus",        WeaponCategory.GAUNTLETS);
        ORE_DICT.put("weaponStaff",          WeaponCategory.STAFF);
        ORE_DICT.put("weaponCrossbow",       WeaponCategory.CROSSBOW);
        ORE_DICT.put("weaponSaber",          WeaponCategory.SABRE);
        ORE_DICT.put("weaponSword",          WeaponCategory.SWORD);
        ORE_DICT.put("toolSword",            WeaponCategory.SWORD);
        ORE_DICT.put("toolAxe",              WeaponCategory.AXE);
    }

    // =========================================================================
    // Tier 4: Tool class strings
    // Some mods register explicit tool classes. Checked as-is and lowercase.
    // =========================================================================
    private static final Map<String, WeaponCategory> TOOL_CLASS_MAP = new HashMap<>();
    static {
        TOOL_CLASS_MAP.put("rapier",           WeaponCategory.RAPIER);
        TOOL_CLASS_MAP.put("katana",           WeaponCategory.KATANA);
        TOOL_CLASS_MAP.put("zweihander",       WeaponCategory.ZWEIHANDER);
        TOOL_CLASS_MAP.put("flamberge",        WeaponCategory.FLAMBERGE);
        TOOL_CLASS_MAP.put("executioner",      WeaponCategory.EXECUTIONER_SWORD);
        TOOL_CLASS_MAP.put("falchion",         WeaponCategory.FALCHION);
        TOOL_CLASS_MAP.put("machete",          WeaponCategory.FALCHION);
        TOOL_CLASS_MAP.put("scimitar",         WeaponCategory.SABRE);
        TOOL_CLASS_MAP.put("tulwar",           WeaponCategory.SABRE);
        TOOL_CLASS_MAP.put("blade",            WeaponCategory.BROADSWORD);
        TOOL_CLASS_MAP.put("quarterstaff",     WeaponCategory.QUARTERSTAFF);
        TOOL_CLASS_MAP.put("battlestaff",      WeaponCategory.QUARTERSTAFF);
        TOOL_CLASS_MAP.put("whip",             WeaponCategory.WHIP);
        TOOL_CLASS_MAP.put("trident",          WeaponCategory.TRIDENT);
        TOOL_CLASS_MAP.put("sickle",           WeaponCategory.SICKLE);
        TOOL_CLASS_MAP.put("claw",             WeaponCategory.CLAW);
        TOOL_CLASS_MAP.put("nunchaku",         WeaponCategory.NUNCHAKU);
        TOOL_CLASS_MAP.put("nunchuck",         WeaponCategory.NUNCHAKU);
        TOOL_CLASS_MAP.put("greatsword",       WeaponCategory.GREATSWORD);
        TOOL_CLASS_MAP.put("broadsword",       WeaponCategory.BROADSWORD);
        TOOL_CLASS_MAP.put("longsword",        WeaponCategory.LONGSWORD);
        TOOL_CLASS_MAP.put("shortsword",       WeaponCategory.SHORTSWORD);
        TOOL_CLASS_MAP.put("sabre",            WeaponCategory.SABRE);
        TOOL_CLASS_MAP.put("saber",            WeaponCategory.SABRE);
        TOOL_CLASS_MAP.put("dagger",           WeaponCategory.DAGGER);
        TOOL_CLASS_MAP.put("knife",            WeaponCategory.HUNTERS_KNIFE);
        TOOL_CLASS_MAP.put("spear",            WeaponCategory.SPEAR);
        TOOL_CLASS_MAP.put("halberd",          WeaponCategory.HALBERD);
        TOOL_CLASS_MAP.put("glaive",           WeaponCategory.GLAIVE);
        TOOL_CLASS_MAP.put("lance",            WeaponCategory.LANCE);
        TOOL_CLASS_MAP.put("polearm",          WeaponCategory.POLEARM);
        TOOL_CLASS_MAP.put("scythe",           WeaponCategory.SCYTHE);
        TOOL_CLASS_MAP.put("mace",             WeaponCategory.MACE);
        TOOL_CLASS_MAP.put("warhammer",        WeaponCategory.WARHAMMER);
        TOOL_CLASS_MAP.put("hammer",           WeaponCategory.HAMMER);
        TOOL_CLASS_MAP.put("flail",            WeaponCategory.FLAIL);
        TOOL_CLASS_MAP.put("battleaxe",        WeaponCategory.BATTLEAXE);
        TOOL_CLASS_MAP.put("handaxe",          WeaponCategory.HANDAXE);
        TOOL_CLASS_MAP.put("cleaver",          WeaponCategory.CLEAVER);
        TOOL_CLASS_MAP.put("gauntlet",         WeaponCategory.GAUNTLETS);
        TOOL_CLASS_MAP.put("staff",            WeaponCategory.STAFF);
        TOOL_CLASS_MAP.put("sword",            WeaponCategory.SWORD);
        TOOL_CLASS_MAP.put("axe",              WeaponCategory.AXE);
    }

    // =========================================================================
    // Tiers 5 & 9: Keyword rules (registry name and display name)
    // regConf  — confidence when matched against registry name path
    // dispConf — confidence when matched against display name
    // =========================================================================
    private static final class KeywordRule {
        final Pattern pattern;
        final WeaponCategory category;
        final float regConf;
        final float dispConf;
        KeywordRule(String regex, WeaponCategory cat, float r, float d) {
            this.pattern  = Pattern.compile("(?i).*(" + regex + ").*");
            this.category = cat;
            this.regConf  = r;
            this.dispConf = d;
        }
    }

    private static final List<KeywordRule> KEYWORD_RULES = new ArrayList<>();
    static {
        Object[][] defs = {
            // ── Swords: specific names first, compound "blade" patterns, then fallbacks ──
            { "rapier|estoc|foil|epee|needle.sword|spike.sword|skewer",     "RAPIER",             0.85f, 0.70f },
            { "katana|tachi|nodachi|odachi",                                "KATANA",             0.85f, 0.70f },
            // Wakizashi/ninjato are shorter companion blades → shortsword not katana
            { "wakizashi|ninjato|chokuto|shinai",                           "SHORTSWORD",         0.85f, 0.70f },
            { "zweihander|zweihänder|zweihaender|two.handed.sword",         "ZWEIHANDER",         0.85f, 0.70f },
            { "flamberge|flambard|montante|spadone|kriegsmesser",           "FLAMBERGE",          0.85f, 0.70f },
            { "executioner|headsman|decapitator",                           "EXECUTIONER_SWORD",  0.85f, 0.70f },
            // Falchion family: curved/chopping single-edge swords and heavy cutting blades
            { "falchion|khopesh|kopis|makhaira|falx|machete|bolo.sword",    "FALCHION",           0.85f, 0.70f },
            { "greatsword|claymore|giant.sword|giant.blade|great.blade",    "GREATSWORD",         0.85f, 0.70f },
            { "broadsword|broad.sword|warsword|war.sword|bastard.sword|broad.blade|cinquedea", "BROADSWORD", 0.85f, 0.70f },
            { "longsword|long.sword|arming.sword|long.blade|jian|estoque",  "LONGSWORD",          0.85f, 0.70f },
            { "shortsword|short.sword|gladius|xiphos|short.blade",          "SHORTSWORD",         0.85f, 0.70f },
            // Sabre family: curved/one-edged swords from many traditions
            { "sabre|saber|cutlass|scimitar|shamshir|tulwar|talwar|dao.sword|falcata|kampilan|slasher", "SABRE", 0.85f, 0.70f },
            // "blade" / "edge" alone → broadsword — covers terra_blade, frost_blade, keen_edge, etc.
            { "blade",                                                      "BROADSWORD",         0.60f, 0.50f },
            { "edge",                                                       "BROADSWORD",         0.55f, 0.45f },

            // ── Knives ──
            { "stiletto|kris|dirk|kunai|rondel|shiv|throwing.dagger|parrying.dagger|tanto|sai|katar|jambiya|balisong|butterfly.knife|push.dagger", "DAGGER", 0.85f, 0.65f },
            { "dagger",                                                     "DAGGER",             0.85f, 0.65f },
            { "bowie|hunting.knife|hunter.knife|combat.knife|skinner|boot.knife|fighting.knife|survival.knife", "HUNTERS_KNIFE", 0.85f, 0.65f },
            { "knife",                                                      "HUNTERS_KNIFE",      0.80f, 0.60f },
            // Tooth/fang/thorn — fantasy creature weapons that function as knives/daggers
            { "tooth.blade|fang.blade|bone.dagger|claw.dagger|venom.fang",  "DAGGER",             0.75f, 0.60f },

            // ── Polearms ──
            { "lance|lancer",                                               "LANCE",              0.85f, 0.70f },
            { "trident",                                                    "TRIDENT",            0.85f, 0.70f },
            // Bladed polearms with wide heads → glaive family
            { "glaive|voulge|naginata|fauchard|partisan|esponton|ranseur|brandistock", "GLAIVE", 0.85f, 0.70f },
            // Hooked/axe-head polearms → halberd family
            { "halberd|bardiche|guisarme|bill|billhook|war.scythe.pole",    "HALBERD",            0.85f, 0.70f },
            { "spear|pike|javelin|assegai|iklwa",                           "SPEAR",              0.85f, 0.70f },
            { "scythe|warscythe|war.scythe|reaper|harvester",              "SCYTHE",             0.85f, 0.70f },
            { "polearm|poleaxe|pole.arm",                                   "POLEARM",            0.80f, 0.65f },

            // ── Blunt ──
            // War picks are closer to warhammer in use — heavy, armour-punching
            { "warhammer|war.hammer|maul|sledge|sledgehammer|war.pick|warpick|bec.de.corbin|mattock|crusher|smasher|skullcrusher", "WARHAMMER", 0.85f, 0.70f },
            { "morningstar|morning.star|flail|ball.chain|chain.mace",       "FLAIL",              0.85f, 0.70f },
            { "mace|bludgeon|truncheon|cosh",                               "MACE",               0.85f, 0.70f },
            { "hammer",                                                     "HAMMER",             0.80f, 0.65f },
            { "club|cudgel|blackjack",                                      "MACE",               0.60f, 0.50f },

            // ── Axes ──
            { "battleaxe|battle.axe|greataxe|great.axe",                   "BATTLEAXE",          0.85f, 0.70f },
            { "lumberaxe|lumber.axe|felling.axe|woodcutting.axe|splitting.axe|splitting.maul|wood.axe", "LUMBERAXE", 0.85f, 0.70f },
            { "cleaver|chopper|headchopper",                               "CLEAVER",            0.85f, 0.70f },
            { "tomahawk|handaxe|hand.axe|hatchet|throwing.axe|francisca|chakram", "HANDAXE",     0.85f, 0.70f },

            // ── Other melee ──
            { "sickle|kama",                                                "SICKLE",             0.85f, 0.70f },
            { "nunchaku|nunchuck|nunchuk|tonfa",                            "NUNCHAKU",           0.85f, 0.70f },
            // Compound claw names first (specific), then bare "claw" (lower confidence)
            { "claw.blade|clawblade|claw.weapon|weapon.claw|iron.claw|steel.claw|bear.claw|dragon.claw|tiger.claw|eagle.talon|talon.blade", "CLAW", 0.85f, 0.70f },
            { "ripper|rending|render.claw|flesh.ripper|bone.ripper",       "CLAW",               0.75f, 0.60f },
            { "talon|claw",                                                 "CLAW",               0.70f, 0.60f },
            { "quarterstaff|quarter.staff|battlestaff|battle.staff|bo.staff|bo.stick", "QUARTERSTAFF", 0.85f, 0.70f },
            { "whip|bullwhip|war.whip|chain.whip|lash|rope.dart|meteor.hammer|flying.claw", "WHIP", 0.85f, 0.70f },
            { "caestus|cestus|knuckle|brass.knuckle|fist.weapon|gauntlet|binding|handwrap|wraps", "GAUNTLETS", 0.85f, 0.65f },

            // ── Ranged ──
            { "crossbow|cross.bow",                                         "CROSSBOW",           0.85f, 0.70f },
            { "shortbow|short.bow|recurve.bow|composite.bow",               "SHORTBOW",           0.85f, 0.70f },
            { "longbow|long.bow|war.bow|warbow|greatbow",                   "LONGBOW",            0.85f, 0.70f },

            // ── Magic / stave ──
            { "staff|wand|scepter|sceptre|rod|stave|catalyst|arcane.focus|spellblade", "STAFF",  0.70f, 0.55f },

            // ── Generic fallbacks — must not beat any specific rule above ──
            { "sword",                                                      "SWORD",              0.50f, 0.40f },
            { "axe",                                                        "AXE",                0.50f, 0.40f },
            { "bow",                                                        "BOW",                0.50f, 0.40f },
        };
        for (Object[] d : defs) {
            KEYWORD_RULES.add(new KeywordRule(
                (String) d[0], WeaponCategory.valueOf((String) d[1]), (Float) d[2], (Float) d[3]));
        }
    }

    // =========================================================================
    // Tier 7: Speed + damage stat profiles
    // Each profile defines expected speed and damage ranges for a weapon category.
    // Items whose stats fall inside a profile's ranges get a vote for that category.
    // Using ranges (not exact matches) accommodates material-based stat variation.
    // =========================================================================
    private static final class StatProfile {
        final WeaponCategory category;
        final double minSpeed, maxSpeed;
        final double minDamage, maxDamage;
        final float confidence;
        StatProfile(WeaponCategory cat, double minSpd, double maxSpd,
                    double minDmg, double maxDmg, float conf) {
            category   = cat;
            minSpeed   = minSpd; maxSpeed   = maxSpd;
            minDamage  = minDmg; maxDamage  = maxDmg;
            confidence = conf;
        }
        boolean matches(double spd, double dmg) {
            return spd >= minSpeed && spd <= maxSpeed
                && dmg >= minDamage && dmg <= maxDamage;
        }
    }

    // Profiles ordered most-specific first. An item can match multiple profiles;
    // the first (most-specific) match is used.
    private static final List<StatProfile> STAT_PROFILES = new ArrayList<>();
    static {
        // speed range     damage range
        // Flamberge and executioner's sword share greatsword speed range but
        // are identified by name/OreDict — no separate stat profile needed.
        // Falchion overlaps broadsword in stats; name/OreDict handles distinction.
        // Sickle: fast + low damage (one-handed farm tool)
        // Zweihander: very slow + very high damage, like greatsword but wider
        STAT_PROFILES.add(new StatProfile(WeaponCategory.ZWEIHANDER,  0.50, 0.90, 9.0, 18.0, 0.60f));
        // Whip: fast, low damage — the reach is what makes it, not raw power
        STAT_PROFILES.add(new StatProfile(WeaponCategory.WHIP,        1.60, 9.9,  0.5,  4.0, 0.65f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.SICKLE,      1.80, 9.9,  1.0,  5.0, 0.60f));
        // Nunchaku: fast chain weapon, moderate damage
        STAT_PROFILES.add(new StatProfile(WeaponCategory.NUNCHAKU,    1.60, 2.60, 3.0,  8.0, 0.55f));
        // Claw: very fast + low damage (fast attack weapon)
        STAT_PROFILES.add(new StatProfile(WeaponCategory.CLAW,        2.00, 9.9,  1.0,  5.5, 0.60f));
        // Trident: medium speed, medium-high damage polearm
        STAT_PROFILES.add(new StatProfile(WeaponCategory.TRIDENT,     0.90, 1.50, 6.0, 12.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.RAPIER,      2.20, 9.9,  1.0,  7.0, 0.70f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.KATANA,      1.80, 2.50, 5.0, 10.0, 0.65f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.DAGGER,      1.90, 9.9,  1.0,  4.5, 0.65f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.HUNTERS_KNIFE,1.70, 9.9, 1.0,  5.5, 0.60f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.SHORTSWORD,  1.40, 2.00, 3.0,  8.0, 0.65f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.LONGSWORD,   1.20, 1.75, 5.0, 10.0, 0.60f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.BROADSWORD,  0.85, 1.40, 6.0, 11.0, 0.60f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.GREATSWORD,  0.50, 1.00, 8.0, 18.0, 0.65f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.LANCE,       0.70, 1.30, 7.0, 14.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.SPEAR,       1.00, 1.70, 5.0, 10.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.HALBERD,     0.70, 1.20, 7.0, 14.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.MACE,        1.10, 1.80, 5.0, 10.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.HAMMER,      1.10, 1.70, 5.0, 10.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.WARHAMMER,   0.40, 0.95, 8.0, 18.0, 0.65f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.HANDAXE,     0.90, 1.60, 6.0, 12.0, 0.55f));
        STAT_PROFILES.add(new StatProfile(WeaponCategory.BATTLEAXE,   0.50, 1.00, 9.0, 18.0, 0.65f));
    }

    // =========================================================================
    // Main classify entry point
    // =========================================================================
    public static ClassificationResult classify(ItemStack stack) {
        if (stack.isEmpty()) return ClassificationResult.NONE;

        // Tier 2: Vanilla explicit — no heuristics, just a map lookup
        ClassificationResult v = classifyVanilla(stack);
        if (v != ClassificationResult.NONE) return v;

        // Tier 3: OreDictionary — authoritative mod-compat signal
        ClassificationResult ore = classifyByOreDict(stack);
        if (ore.confidence >= 0.9f) return ore;

        // Tiers 4-9: Accumulate evidence from all remaining signals.
        // The category with the highest total accumulated confidence wins.
        // When multiple independent signals agree on the same category,
        // the combined evidence is much stronger than any single signal.
        EnumMap<WeaponCategory, Float> evidence = new EnumMap<>(WeaponCategory.class);

        // Carry forward any weak OreDict result (e.g. generic "weaponSword")
        if (ore != ClassificationResult.NONE) accum(evidence, ore.category, ore.confidence);

        // Tier 4: Tool class strings
        accum(evidence, classifyByToolClass(stack));

        // Tier 5: Registry name keywords
        accum(evidence, classifyByRegistryName(stack));

        // Tier 6: Item class hierarchy + attribute speed refinement
        accum(evidence, classifyByClass(stack));

        // Tier 7: Speed + damage stat profiles
        accum(evidence, classifyByStats(stack));

        // Tier 8: High reach attribute → polearm family
        accum(evidence, classifyByReach(stack));

        // Tier 9: Display name keywords (only if name heuristics enabled)
        if (BromaxBattleConfig.enableNameHeuristics) {
            accum(evidence, classifyByDisplayName(stack));
        }

        // Tier 10: Attack damage last resort — only if nothing else voted
        if (evidence.isEmpty()) {
            return classifyByAttackDamage(stack);
        }

        // Find the category with the most accumulated evidence
        WeaponCategory winner = null;
        float winnerTotal = 0f;
        float secondTotal = 0f;
        for (Map.Entry<WeaponCategory, Float> e : evidence.entrySet()) {
            if (e.getValue() > winnerTotal) {
                secondTotal = winnerTotal;
                winnerTotal = e.getValue();
                winner = e.getKey();
            } else if (e.getValue() > secondTotal) {
                secondTotal = e.getValue();
            }
        }
        if (winner == null) return ClassificationResult.NONE;

        // Agreement boost: when the winner pulled significantly more evidence than
        // the second-place category, cap at 0.95; otherwise let evidence sum speak.
        // Normalise: divide by 1.5 (two strong signals = 1.0 confidence).
        float finalConf = Math.min(0.95f, winnerTotal / 1.5f);
        return new ClassificationResult(winner, finalConf);
    }

    // =========================================================================
    // Tier implementations
    // =========================================================================

    private static ClassificationResult classifyVanilla(ItemStack stack) {
        ResourceLocation reg = stack.getItem().getRegistryName();
        if (reg == null) return ClassificationResult.NONE;
        WeaponCategory cat = VANILLA.get(reg.toString());
        return cat != null ? new ClassificationResult(cat, 0.95f) : ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByOreDict(ItemStack stack) {
        int[] ids = OreDictionary.getOreIDs(stack);
        if (ids.length == 0) return ClassificationResult.NONE;
        for (Map.Entry<String, WeaponCategory> entry : ORE_DICT.entrySet()) {
            int targetId = OreDictionary.getOreID(entry.getKey());
            if (targetId != -1 && containsId(ids, targetId)) {
                return new ClassificationResult(entry.getValue(), 0.9f);
            }
        }
        return ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByToolClass(ItemStack stack) {
        Set<String> classes = stack.getItem().getToolClasses(stack);
        if (classes.isEmpty()) return ClassificationResult.NONE;
        for (String cls : classes) {
            WeaponCategory cat = TOOL_CLASS_MAP.get(cls.toLowerCase(java.util.Locale.ROOT));
            if (cat != null) return new ClassificationResult(cat, 0.80f);
        }
        return ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByRegistryName(ItemStack stack) {
        ResourceLocation reg = stack.getItem().getRegistryName();
        if (reg == null) return ClassificationResult.NONE;
        String target = reg.toString().replace('/', '_');
        for (KeywordRule rule : KEYWORD_RULES) {
            if (rule.pattern.matcher(target).matches()) {
                return new ClassificationResult(rule.category, rule.regConf);
            }
        }
        return ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByClass(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ItemSword) {
            WeaponCategory refined = refineSwordBySpeed(stack);
            float conf = (refined == WeaponCategory.SWORD) ? 0.55f : 0.75f;
            return new ClassificationResult(refined, conf);
        }
        if (item instanceof ItemAxe) {
            WeaponCategory refined = refineAxeBySpeed(stack);
            float conf = (refined == WeaponCategory.AXE) ? 0.55f : 0.75f;
            return new ClassificationResult(refined, conf);
        }
        if (item instanceof ItemBow)  return new ClassificationResult(WeaponCategory.BOW,     0.80f);
        if (item instanceof ItemHoe)  return new ClassificationResult(WeaponCategory.POLEARM, 0.50f);
        if (item instanceof ItemTool) return new ClassificationResult(WeaponCategory.MACE,    0.45f);
        return ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByStats(ItemStack stack) {
        double speed  = getAttackSpeed(stack);
        double damage = getAttackDamage(stack);
        if (speed <= 0 || damage <= 0) return ClassificationResult.NONE;
        for (StatProfile p : STAT_PROFILES) {
            if (p.matches(speed, damage)) return new ClassificationResult(p.category, p.confidence);
        }
        return ClassificationResult.NONE;
    }

    /**
     * Some mods add an "attackRange" or "reach" generic attribute to polearms.
     * A significant bonus (>= 1.0 block beyond vanilla's 3) strongly suggests a
     * polearm, even when everything else looks like a generic sword.
     */
    private static ClassificationResult classifyByReach(ItemStack stack) {
        // Try both common attribute names used by weapon mods
        for (String attrName : new String[]{ "attackRange", "reach", "attack_range", "generic.attackRange" }) {
            Collection<AttributeModifier> mods = stack
                .getAttributeModifiers(EntityEquipmentSlot.MAINHAND).get(attrName);
            if (mods == null) continue;
            double bonus = 0;
            for (AttributeModifier m : mods) bonus += m.getAmount();
            if (bonus >= 2.0) return new ClassificationResult(WeaponCategory.LANCE,  0.65f);
            if (bonus >= 1.0) return new ClassificationResult(WeaponCategory.SPEAR,  0.65f);
            if (bonus >= 0.5) return new ClassificationResult(WeaponCategory.POLEARM,0.55f);
        }
        return ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByDisplayName(ItemStack stack) {
        String display = stack.getDisplayName();
        for (KeywordRule rule : KEYWORD_RULES) {
            if (rule.pattern.matcher(display).matches()) {
                return new ClassificationResult(rule.category, rule.dispConf);
            }
        }
        return ClassificationResult.NONE;
    }

    private static ClassificationResult classifyByAttackDamage(ItemStack stack) {
        double bonus = getAttackDamage(stack);
        if (bonus <= 0) return ClassificationResult.NONE;
        if (bonus < 2.0) return new ClassificationResult(WeaponCategory.DAGGER,     0.3f);
        if (bonus < 4.0) return new ClassificationResult(WeaponCategory.LONGSWORD,  0.3f);
        if (bonus < 6.0) return new ClassificationResult(WeaponCategory.BATTLEAXE,  0.3f);
        if (bonus < 9.0) return new ClassificationResult(WeaponCategory.GREATSWORD, 0.3f);
        return              new ClassificationResult(WeaponCategory.WARHAMMER,       0.3f);
    }

    // =========================================================================
    // Attribute refinement helpers
    // =========================================================================

    private static WeaponCategory refineSwordBySpeed(ItemStack stack) {
        double speed = getAttackSpeed(stack);
        if (speed <= 0) return WeaponCategory.SWORD;
        // 1.58 threshold (not 1.6): Java FP gives 4.0 + (-2.4) = 1.5999...
        if (speed >= 2.20) return WeaponCategory.RAPIER;
        if (speed >= 1.58) return WeaponCategory.SHORTSWORD;
        if (speed <= 0.90) return WeaponCategory.GREATSWORD;
        if (speed <= 1.20) return WeaponCategory.BROADSWORD;
        return WeaponCategory.LONGSWORD;
    }

    private static WeaponCategory refineAxeBySpeed(ItemStack stack) {
        double speed = getAttackSpeed(stack);
        if (speed <= 0) return WeaponCategory.AXE;
        if (speed >= 1.40) return WeaponCategory.HANDAXE;
        if (speed <= 0.80) return WeaponCategory.BATTLEAXE;
        return WeaponCategory.HANDAXE;
    }

    // =========================================================================
    // Attribute reading helpers
    // =========================================================================

    private static double getAttackSpeed(ItemStack stack) {
        Collection<AttributeModifier> mods = stack
            .getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
            .get(SharedMonsterAttributes.ATTACK_SPEED.getName());
        if (mods.isEmpty()) return 0;
        double speed = 4.0;
        for (AttributeModifier m : mods) speed += m.getAmount();
        return speed;
    }

    private static double getAttackDamage(ItemStack stack) {
        Collection<AttributeModifier> mods = stack
            .getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
            .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName());
        if (mods.isEmpty()) return 0;
        double bonus = 0;
        for (AttributeModifier m : mods) bonus += m.getAmount();
        return bonus;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static void accum(EnumMap<WeaponCategory, Float> map, ClassificationResult r) {
        if (r == null || r == ClassificationResult.NONE || r.confidence <= 0) return;
        accum(map, r.category, r.confidence);
    }

    private static void accum(EnumMap<WeaponCategory, Float> map, WeaponCategory cat, float conf) {
        if (cat == null) return;
        map.merge(cat, conf, Float::sum);
    }

    private static boolean containsId(int[] ids, int target) {
        for (int id : ids) if (id == target) return true;
        return false;
    }
}
