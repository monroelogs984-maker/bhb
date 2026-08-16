package com.bromax.bromaxbattle.weapon;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Multi-signal weapon classifier.
 *
 * Tier 1 (1.00): Per-item JSON override           — WeaponRegistry, before this is called
 * Tier 2 (0.95): Vanilla explicit map             — hardcoded, no failure modes
 * Tier 3 (0.90): Forge item tags                  — replaces OreDictionary from 1.12.2
 * Tier 4 (0.85): Registry name keywords           — set by mod developer, reliable
 * Tier 5 (0.75): Item class + attribute speed     — SwordItem/AxeItem + speed refinement
 * Tier 6 (0.70): Stats profile (speed + damage)   — catches items with no name signals
 * Tier 7 (0.65): Reach attribute                  — high attack range → polearm family
 * Tier 8 (0.65): Display name keywords            — last keyword pass
 * Tier 9 (0.30): Attack damage heuristic          — absolute last resort
 *
 * When multiple independent signals agree the final confidence is boosted.
 */
public class WeaponClassifier {

    private static final Map<WeaponCategory, Float> votes = new EnumMap<>(WeaponCategory.class);

    // ── Tier 2: vanilla explicit map ─────────────────────────────────────────
    private static final Map<Class<? extends Item>, WeaponCategory> VANILLA_MAP = new IdentityHashMap<>();
    static {
        VANILLA_MAP.put(SwordItem.class,     WeaponCategory.SWORD);
        VANILLA_MAP.put(AxeItem.class,       WeaponCategory.AXE);
        VANILLA_MAP.put(TridentItem.class,   WeaponCategory.TRIDENT);
        VANILLA_MAP.put(BowItem.class,       WeaponCategory.BOW);
        VANILLA_MAP.put(CrossbowItem.class,  WeaponCategory.CROSSBOW);
    }

    // ── Tier 4: registry name / display name keyword tables ──────────────────
    private static final List<Map.Entry<Pattern, WeaponCategory>> NAME_PATTERNS = new ArrayList<>();
    static {
        // swords (ordered most-specific first)
        add("executioner",                      WeaponCategory.EXECUTIONER_SWORD);
        add("zweihander|zweihänder|zweihand",   WeaponCategory.ZWEIHANDER);
        add("flamberge",                        WeaponCategory.FLAMBERGE);
        add("greatsword|great_sword",           WeaponCategory.GREATSWORD);
        add("broadsword|broad_sword",           WeaponCategory.BROADSWORD);
        add("longsword|long_sword",             WeaponCategory.LONGSWORD);
        add("shortsword|short_sword",           WeaponCategory.SHORTSWORD);
        add("falchion",                         WeaponCategory.FALCHION);
        add("katana",                           WeaponCategory.KATANA);
        add("sabre|saber",                      WeaponCategory.SABRE);
        add("rapier|estoc",                     WeaponCategory.RAPIER);
        // knives / daggers
        add("dagger|dirk|stiletto",             WeaponCategory.DAGGER);
        add("hunter.*knife|hunting.*knife|knife.*hunter", WeaponCategory.HUNTERS_KNIFE);
        add("knife|kris|katar",                 WeaponCategory.DAGGER);
        // polearms
        add("halberd",                          WeaponCategory.HALBERD);
        add("glaive",                           WeaponCategory.GLAIVE);
        add("lance",                            WeaponCategory.LANCE);
        add("trident",                          WeaponCategory.TRIDENT);
        add("spear|pike|javelin|ranseur",       WeaponCategory.SPEAR);
        add("polearm|pole_arm|naginata|voulge", WeaponCategory.POLEARM);
        // blunt
        add("warhammer|war_hammer|maul",        WeaponCategory.WARHAMMER);
        add("hammer",                           WeaponCategory.HAMMER);
        add("mace|morningstar|flange",          WeaponCategory.MACE);
        add("flail|morning_star",               WeaponCategory.FLAIL);
        // axes
        add("battleaxe|battle_axe|greataxe|great_axe", WeaponCategory.BATTLEAXE);
        add("lumberaxe|lumber_axe|woodcutting",         WeaponCategory.LUMBERAXE);
        add("cleaver|butcher",                  WeaponCategory.CLEAVER);
        add("handaxe|hand_axe|hatchet",         WeaponCategory.HANDAXE);
        // other melee
        add("scythe",                           WeaponCategory.SCYTHE);
        add("sickle",                           WeaponCategory.SICKLE);
        add("gauntlet|brass_knuckle|knuckle",   WeaponCategory.GAUNTLETS);
        add("claw",                             WeaponCategory.CLAW);
        add("nunchaku|nunchuck",                WeaponCategory.NUNCHAKU);
        add("quarterstaff|quarter_staff",       WeaponCategory.QUARTERSTAFF);
        add("whip|flail.*chain",                WeaponCategory.WHIP);
        add("staff|wand|scepter|sceptre",       WeaponCategory.STAFF);
        // ranged
        add("longbow|long_bow",                 WeaponCategory.LONGBOW);
        add("shortbow|short_bow",               WeaponCategory.SHORTBOW);
        add("crossbow|arbalest",                WeaponCategory.CROSSBOW);
    }

    private static void add(String pattern, WeaponCategory cat) {
        NAME_PATTERNS.add(new AbstractMap.SimpleImmutableEntry<>(
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE), cat));
    }

    public static ClassificationResult classify(ItemStack stack) {
        if (stack.isEmpty()) return new ClassificationResult(WeaponCategory.GAUNTLETS, 1.0f);
        Item item = stack.getItem();
        votes.clear();

        // Tier 2: vanilla explicit
        WeaponCategory vanillaCat = VANILLA_MAP.get(item.getClass());
        if (vanillaCat != null) vote(vanillaCat, 0.95f);

        // Tier 3: Forge item tags
        if (stack.is(Tags.Items.TOOLS_SWORDS))    vote(WeaponCategory.SWORD,     0.90f);
        if (stack.is(Tags.Items.TOOLS_AXES))      vote(WeaponCategory.AXE,       0.90f);
        if (stack.is(Tags.Items.TOOLS_BOWS))      vote(WeaponCategory.BOW,       0.90f);
        if (stack.is(Tags.Items.TOOLS_CROSSBOWS)) vote(WeaponCategory.CROSSBOW,  0.90f);
        if (stack.is(Tags.Items.TOOLS_TRIDENTS))  vote(WeaponCategory.TRIDENT,   0.90f);

        // Tier 4: registry name keywords
        net.minecraft.resources.ResourceLocation regLoc = ForgeRegistries.ITEMS.getKey(item);
        String regPath = regLoc != null ? regLoc.getPath().toLowerCase(Locale.ROOT) : "";
        if (!regPath.isEmpty()) {
            for (Map.Entry<Pattern, WeaponCategory> entry : NAME_PATTERNS) {
                if (entry.getKey().matcher(regPath).find()) {
                    vote(entry.getValue(), 0.85f);
                    break;
                }
            }
        }

        // Tier 5: item class + attribute speed
        if (item instanceof SwordItem) {
            double speed = getAttributeValue(stack, EquipmentSlot.MAINHAND,
                    Attributes.ATTACK_SPEED.getDescriptionId());
            // Vanilla sword speed base is 1.6. Faster → lighter sword category.
            if (speed > 2.2) vote(WeaponCategory.SHORTSWORD, 0.75f);
        }
        if (item instanceof AxeItem) {
            double speed = getAttributeValue(stack, EquipmentSlot.MAINHAND,
                    Attributes.ATTACK_SPEED.getDescriptionId());
            if (speed < 0.6) vote(WeaponCategory.BATTLEAXE, 0.75f);
        }

        // Tier 6: stats profile
        double damage = getAttributeValue(stack, EquipmentSlot.MAINHAND,
                Attributes.ATTACK_DAMAGE.getDescriptionId());
        double speed  = getAttributeValue(stack, EquipmentSlot.MAINHAND,
                Attributes.ATTACK_SPEED.getDescriptionId());
        if (damage > 0 || speed != 0) {
            WeaponCategory statCat = guessFromStats(damage, speed);
            if (statCat != null) vote(statCat, 0.70f);
        }

        // Tier 7: reach attribute
        double reach = getAttributeValue(stack, EquipmentSlot.MAINHAND,
                "forge:reach_distance");
        if (reach > 1.5) vote(WeaponCategory.POLEARM, 0.65f);

        // Tier 8: display name keywords (if name heuristics enabled)
        if (BromaxBattleConfig.enableNameHeuristics()) {
            String displayName = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            for (Map.Entry<Pattern, WeaponCategory> entry : NAME_PATTERNS) {
                if (entry.getKey().matcher(displayName).find()) {
                    vote(entry.getValue(), 0.65f);
                    break;
                }
            }
        }

        // Tier 9: damage heuristic — last resort
        if (damage >= 5.0) vote(WeaponCategory.SWORD, 0.30f);

        return pickWinner();
    }

    private static void vote(WeaponCategory cat, float weight) {
        votes.merge(cat, weight, Float::sum);
    }

    private static ClassificationResult pickWinner() {
        if (votes.isEmpty()) return ClassificationResult.NONE;
        WeaponCategory best = null;
        float bestScore = 0;
        int   bestCount = 0;
        for (Map.Entry<WeaponCategory, Float> e : votes.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
                bestCount = 1;
            } else if (e.getValue() == bestScore) {
                bestCount++;
            }
        }
        // Agreement boost: multiple independent signals on same cat raises confidence
        float finalConf = Math.min(1.0f, bestScore * (bestCount > 1 ? 1.1f : 1.0f));
        return best != null ? new ClassificationResult(best, finalConf) : ClassificationResult.NONE;
    }

    private static WeaponCategory guessFromStats(double damage, double speed) {
        // speed values are modifiers (added to base 4.0), not absolute
        if (speed < -3.2 && damage > 8)  return WeaponCategory.WARHAMMER;
        if (speed < -3.0 && damage > 6)  return WeaponCategory.GREATSWORD;
        if (speed < -2.5 && damage > 5)  return WeaponCategory.BATTLEAXE;
        if (speed > -1.0 && damage < 3)  return WeaponCategory.DAGGER;
        if (speed > -1.5 && damage < 5)  return WeaponCategory.SHORTSWORD;
        if (speed < -2.0 && damage > 3)  return WeaponCategory.LONGSWORD;
        return null;
    }

    private static double getAttributeValue(ItemStack stack, EquipmentSlot slot, String attrId) {
        try {
            for (Map.Entry<net.minecraft.world.entity.ai.attributes.Attribute,
                           AttributeModifier> e : stack.getAttributeModifiers(slot).entries()) {
                if (e.getKey().getDescriptionId().equals(attrId)) {
                    return e.getValue().getAmount();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
