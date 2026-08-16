package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

public class AoeCalculator {

    private static final class Profile {
        final float radius;
        final float cosHalfArc; // precomputed cos(halfArc) for fast dot-product test
        final float damageMult;

        Profile(float radius, float halfArcDeg, float damageMult) {
            this.radius      = radius;
            this.cosHalfArc  = (float) Math.cos(Math.toRadians(halfArcDeg));
            this.damageMult  = damageMult;
        }
    }

    // Hard cap on AOE targets per swing — prevents a dungeon full of mobs from turning
    // one attack into 50 simultaneous attackEntityFrom calls in a single tick.
    static final int MAX_TARGETS = 8;

    // Per-category AOE shape: (reach in blocks, half-arc in degrees, damage fraction of base)
    private static final EnumMap<WeaponCategory, Profile> PROFILES = new EnumMap<>(WeaponCategory.class);
    static {
        // ── Swords ──────────────────────────────────────────────────────────
        PROFILES.put(WeaponCategory.SHORTSWORD,       new Profile(2.2f,  50f, 0.55f));
        PROFILES.put(WeaponCategory.LONGSWORD,        new Profile(2.6f,  55f, 0.60f));
        PROFILES.put(WeaponCategory.BROADSWORD,       new Profile(2.8f,  65f, 0.65f));
        PROFILES.put(WeaponCategory.SABRE,            new Profile(2.5f,  70f, 0.60f));
        PROFILES.put(WeaponCategory.RAPIER,           new Profile(2.8f,  18f, 0.45f));
        PROFILES.put(WeaponCategory.GREATSWORD,        new Profile(3.2f,  70f, 0.70f));
        PROFILES.put(WeaponCategory.ZWEIHANDER,        new Profile(3.5f,  80f, 0.70f)); // wider sweep than greatsword, crowd-clearing
        PROFILES.put(WeaponCategory.KATANA,           new Profile(2.5f,  55f, 0.58f));
        PROFILES.put(WeaponCategory.FLAMBERGE,        new Profile(3.2f,  75f, 0.68f)); // wide dramatic sweeps, two-handed
        PROFILES.put(WeaponCategory.EXECUTIONER_SWORD,new Profile(2.6f,  38f, 0.75f)); // narrow but devastating overhead
        PROFILES.put(WeaponCategory.FALCHION,         new Profile(2.4f,  62f, 0.58f)); // curved chopping arc
        PROFILES.put(WeaponCategory.SWORD,            new Profile(2.5f,  55f, 0.60f)); // fallback
        // ── Knives ──────────────────────────────────────────────────────────
        PROFILES.put(WeaponCategory.DAGGER,      new Profile(1.5f,  35f, 0.45f));
        PROFILES.put(WeaponCategory.HUNTERS_KNIFE,new Profile(1.8f, 40f, 0.50f));
        // ── Polearms ────────────────────────────────────────────────────────
        PROFILES.put(WeaponCategory.SPEAR,        new Profile(3.5f,  20f, 0.55f));
        PROFILES.put(WeaponCategory.TRIDENT,      new Profile(3.3f,  38f, 0.58f)); // wider than spear, has sweep
        PROFILES.put(WeaponCategory.HALBERD,      new Profile(3.2f,  50f, 0.65f));
        PROFILES.put(WeaponCategory.GLAIVE,       new Profile(3.4f,  80f, 0.65f));
        PROFILES.put(WeaponCategory.LANCE,        new Profile(4.0f,  15f, 0.60f));
        PROFILES.put(WeaponCategory.POLEARM,      new Profile(3.0f,  35f, 0.60f)); // fallback
        // ── Blunt ───────────────────────────────────────────────────────────
        PROFILES.put(WeaponCategory.MACE,        new Profile(2.0f,  40f, 0.55f));
        PROFILES.put(WeaponCategory.WARHAMMER,   new Profile(2.2f,  35f, 0.70f));
        PROFILES.put(WeaponCategory.HAMMER,      new Profile(1.8f,  30f, 0.55f));
        PROFILES.put(WeaponCategory.FLAIL,       new Profile(2.8f,  60f, 0.55f));
        // ── Axes ────────────────────────────────────────────────────────────
        PROFILES.put(WeaponCategory.HANDAXE,     new Profile(2.0f,  45f, 0.55f));
        PROFILES.put(WeaponCategory.BATTLEAXE,   new Profile(2.6f,  55f, 0.65f));
        PROFILES.put(WeaponCategory.CLEAVER,     new Profile(2.2f,  55f, 0.60f));
        PROFILES.put(WeaponCategory.LUMBERAXE,   new Profile(2.8f,  50f, 0.65f));
        PROFILES.put(WeaponCategory.AXE,         new Profile(2.0f,  40f, 0.55f)); // fallback
        // ── Other melee ─────────────────────────────────────────────────────
        PROFILES.put(WeaponCategory.SCYTHE,       new Profile(3.2f,  80f, 0.60f));
        PROFILES.put(WeaponCategory.SICKLE,       new Profile(2.0f,  55f, 0.50f)); // one-handed curved reap
        PROFILES.put(WeaponCategory.GAUNTLETS,    new Profile(1.5f,  40f, 0.45f));
        PROFILES.put(WeaponCategory.CLAW,         new Profile(1.6f,  50f, 0.48f)); // close range multi-strike
        PROFILES.put(WeaponCategory.NUNCHAKU,      new Profile(2.0f,  65f, 0.50f)); // wide spinning arc
        PROFILES.put(WeaponCategory.QUARTERSTAFF, new Profile(2.8f,  58f, 0.55f)); // wide arc, both ends can hit
        PROFILES.put(WeaponCategory.WHIP,         new Profile(5.0f,  10f, 0.45f)); // longest reach in the mod, very narrow
        PROFILES.put(WeaponCategory.STAFF,        new Profile(2.2f,  35f, 0.50f));
        // Ranged weapons have no melee AOE
    }

    public static boolean hasAoe(WeaponCategory cat) {
        return PROFILES.containsKey(cat);
    }

    public static float getDamageMult(WeaponCategory cat) {
        Profile p = PROFILES.get(cat);
        return p != null ? p.damageMult : 0f;
    }

    /**
     * Returns all living entities within the weapon's AOE arc, excluding the primary
     * target and any entity that is dead or currently in iframes.
     *
     * Uses the player's horizontal yaw as the swing direction. No Y-axis cone — a
     * ground-level enemy and a fly at shoulder height both count if they're in the
     * horizontal arc, which matches how every vanilla and mod combat system works.
     */
    public static List<EntityLivingBase> getTargets(EntityPlayer player,
                                                     Entity primaryTarget,
                                                     WeaponCategory cat) {
        Profile p = PROFILES.get(cat);
        if (p == null) return Collections.emptyList();

        // baseReach scales all weapon radii proportionally; default 3.5 = 1.0x.
        float effectiveRadius = p.radius * (BromaxBattleConfig.baseReach / 3.5f);

        List<Entity> nearby = player.world.getEntitiesWithinAABBExcludingEntity(
                player, player.getEntityBoundingBox().grow(effectiveRadius));
        if (nearby.isEmpty()) return Collections.emptyList();

        // Horizontal forward vector from player yaw
        double yawRad = Math.toRadians(player.rotationYaw);
        double fx = -Math.sin(yawRad);
        double fz =  Math.cos(yawRad);

        List<EntityLivingBase> result = new ArrayList<>();
        for (Entity entity : nearby) {
            if (entity == null) continue;       // guard against null entries from buggy mods
            if (entity.isDead) continue;
            if (entity == primaryTarget) continue;
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            if (living.hurtResistantTime > 0) continue;

            // Horizontal direction from player to entity
            double dx = entity.posX - player.posX;
            double dz = entity.posZ - player.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.01) continue; // entity standing exactly on the player

            if (dist > effectiveRadius) continue;
            double dot = (dx / dist) * fx + (dz / dist) * fz;
            if (dot < p.cosHalfArc) continue;

            result.add(living);
            if (result.size() >= MAX_TARGETS) break;
        }
        return result;
    }
}
