package com.bromax.bromaxbattle.api;

import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;

import javax.annotation.Nullable;

/**
 * Posted on the NeoForge event bus (server side) after a BHB melee attack lands its primary hit.
 * Delayed hits post when they fire; immediate hits post as the attack is processed.
 */
public class BhbHitEvent extends Event {
    private final Player attacker;
    private final Entity target;
    @Nullable private final AttackDefinition attack;
    @Nullable private final WeaponCategory category;
    private final boolean crit;

    public BhbHitEvent(Player attacker, Entity target, @Nullable AttackDefinition attack,
                       @Nullable WeaponCategory category, boolean crit) {
        this.attacker = attacker;
        this.target = target;
        this.attack = attack;
        this.category = category;
        this.crit = crit;
    }

    public Player getAttacker() { return attacker; }
    public Entity getTarget() { return target; }
    /** The rolled variant (default/heavy/light), or null for non-BHB items. */
    @Nullable public AttackDefinition getAttack() { return attack; }
    @Nullable public WeaponCategory getCategory() { return category; }
    public boolean isCrit() { return crit; }

    /** True for the heavy variant (slower than default), the "charged" swing. */
    public boolean isHeavy() { return attack != null && attack.speedMultiplier < 0.99f; }
    /** True for the light variant (faster than default). */
    public boolean isLight() { return attack != null && attack.speedMultiplier > 1.01f; }
}
