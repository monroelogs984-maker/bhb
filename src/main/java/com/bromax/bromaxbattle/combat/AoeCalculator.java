package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.*;

public class AoeCalculator {

    private static final class Profile {
        final float radius;
        final float cosHalfArc;
        final float damageMult;

        Profile(float radius, float halfArcDeg, float damageMult) {
            this.radius     = radius;
            this.cosHalfArc = (float) Math.cos(Math.toRadians(halfArcDeg));
            this.damageMult = damageMult;
        }
    }

    static final int MAX_TARGETS = 8;

    private static final EnumMap<WeaponCategory, Profile> PROFILES = new EnumMap<>(WeaponCategory.class);
    static {
        PROFILES.put(WeaponCategory.SHORTSWORD,        new Profile(2.2f,  50f, 0.55f));
        PROFILES.put(WeaponCategory.LONGSWORD,         new Profile(2.6f,  55f, 0.60f));
        PROFILES.put(WeaponCategory.BROADSWORD,        new Profile(2.8f,  65f, 0.65f));
        PROFILES.put(WeaponCategory.SABRE,             new Profile(2.5f,  70f, 0.60f));
        PROFILES.put(WeaponCategory.RAPIER,            new Profile(2.8f,  18f, 0.45f));
        PROFILES.put(WeaponCategory.GREATSWORD,        new Profile(3.2f,  70f, 0.70f));
        PROFILES.put(WeaponCategory.ZWEIHANDER,        new Profile(3.5f,  80f, 0.70f));
        PROFILES.put(WeaponCategory.KATANA,            new Profile(2.5f,  55f, 0.58f));
        PROFILES.put(WeaponCategory.FLAMBERGE,         new Profile(3.2f,  75f, 0.68f));
        PROFILES.put(WeaponCategory.EXECUTIONER_SWORD, new Profile(2.6f,  38f, 0.75f));
        PROFILES.put(WeaponCategory.FALCHION,          new Profile(2.4f,  62f, 0.58f));
        PROFILES.put(WeaponCategory.SWORD,             new Profile(2.5f,  55f, 0.60f));
        PROFILES.put(WeaponCategory.DAGGER,            new Profile(1.5f,  35f, 0.45f));
        PROFILES.put(WeaponCategory.HUNTERS_KNIFE,     new Profile(1.8f,  40f, 0.50f));
        PROFILES.put(WeaponCategory.SPEAR,             new Profile(3.5f,  20f, 0.55f));
        PROFILES.put(WeaponCategory.TRIDENT,           new Profile(3.3f,  38f, 0.58f));
        PROFILES.put(WeaponCategory.HALBERD,           new Profile(3.2f,  50f, 0.65f));
        PROFILES.put(WeaponCategory.GLAIVE,            new Profile(3.4f,  80f, 0.65f));
        PROFILES.put(WeaponCategory.LANCE,             new Profile(4.0f,  15f, 0.60f));
        PROFILES.put(WeaponCategory.POLEARM,           new Profile(3.0f,  35f, 0.60f));
        PROFILES.put(WeaponCategory.MACE,              new Profile(2.0f,  40f, 0.55f));
        PROFILES.put(WeaponCategory.WARHAMMER,         new Profile(2.2f,  35f, 0.70f));
        PROFILES.put(WeaponCategory.HAMMER,            new Profile(1.8f,  30f, 0.55f));
        PROFILES.put(WeaponCategory.FLAIL,             new Profile(2.8f,  60f, 0.55f));
        PROFILES.put(WeaponCategory.HANDAXE,           new Profile(2.0f,  45f, 0.55f));
        PROFILES.put(WeaponCategory.BATTLEAXE,         new Profile(2.6f,  55f, 0.65f));
        PROFILES.put(WeaponCategory.CLEAVER,           new Profile(2.2f,  55f, 0.60f));
        PROFILES.put(WeaponCategory.LUMBERAXE,         new Profile(2.8f,  50f, 0.65f));
        PROFILES.put(WeaponCategory.AXE,               new Profile(2.0f,  40f, 0.55f));
        PROFILES.put(WeaponCategory.SCYTHE,            new Profile(3.2f,  80f, 0.60f));
        PROFILES.put(WeaponCategory.SICKLE,            new Profile(2.0f,  55f, 0.50f));
        PROFILES.put(WeaponCategory.GAUNTLETS,         new Profile(1.5f,  40f, 0.45f));
        PROFILES.put(WeaponCategory.CLAW,              new Profile(1.6f,  50f, 0.48f));
        PROFILES.put(WeaponCategory.NUNCHAKU,          new Profile(2.0f,  65f, 0.50f));
        PROFILES.put(WeaponCategory.QUARTERSTAFF,      new Profile(2.8f,  58f, 0.55f));
        PROFILES.put(WeaponCategory.WHIP,              new Profile(5.0f,  10f, 0.45f));
        PROFILES.put(WeaponCategory.STAFF,             new Profile(2.2f,  35f, 0.50f));
    }

    public static boolean hasAoe(WeaponCategory cat) {
        return PROFILES.containsKey(cat);
    }

    public static float getDamageMult(WeaponCategory cat) {
        Profile p = PROFILES.get(cat);
        return p != null ? p.damageMult : 0f;
    }

    public static List<LivingEntity> getTargets(Player player, Entity primaryTarget, WeaponCategory cat) {
        Profile p = PROFILES.get(cat);
        if (p == null) return Collections.emptyList();

        float effectiveRadius = p.radius * (BromaxBattleConfig.baseReach() / 3.5f);

        AABB searchBox = player.getBoundingBox().inflate(effectiveRadius);
        List<LivingEntity> nearby = player.level.getEntitiesOfClass(
                LivingEntity.class, searchBox, e -> e != player);
        if (nearby.isEmpty()) return Collections.emptyList();

        double yawRad = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yawRad);
        double fz =  Math.cos(yawRad);

        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : nearby) {
            if (entity.isDeadOrDying()) continue;
            if (entity == primaryTarget) continue;
            if (entity.invulnerableTime > 0) continue;

            double dx   = entity.getX() - player.getX();
            double dz   = entity.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.01 || dist > effectiveRadius) continue;
            double dot = (dx / dist) * fx + (dz / dist) * fz;
            if (dot < p.cosHalfArc) continue;

            result.add(entity);
            if (result.size() >= MAX_TARGETS) break;
        }
        return result;
    }
}
