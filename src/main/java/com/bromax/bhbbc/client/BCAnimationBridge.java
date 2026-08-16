package com.bromax.bhbbc.client;

import com.bromax.bhbbc.BhbBc;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bettercombat.client.animation.PlayerAttackAnimatable;
import net.bettercombat.logic.AnimatedHand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class BCAnimationBridge {

    public record AnimMapping(String animation, AnimatedHand hand, float upswing) {}

    private static final Map<String, AnimMapping> MAPPING = new HashMap<>();

    static {
        String path = "assets/bhb_bc/animation_mapping.json";
        try (InputStream is = BCAnimationBridge.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonObject m = entry.getValue().getAsJsonObject();
                    String anim    = m.get("animation").getAsString();
                    AnimatedHand h = AnimatedHand.valueOf(m.get("hand").getAsString());
                    float upswing  = m.has("upswing") ? m.get("upswing").getAsFloat() : 0.45f;
                    MAPPING.put(entry.getKey(), new AnimMapping(anim, h, upswing));
                }
                BhbBc.LOGGER.info("[bhb_bc] Loaded {} animation mappings", MAPPING.size());
            } else {
                BhbBc.LOGGER.error("[bhb_bc] animation_mapping.json not found");
            }
        } catch (Exception e) {
            BhbBc.LOGGER.error("[bhb_bc] Failed to load animation_mapping.json: {}", e.getMessage());
        }
    }

    /**
     * Returns true if a BC animation was triggered (mapping exists), false if the
     * BHB animation has no mapping — the mixin uses this to decide whether to
     * also cancel BHB's play() call (and thus skip BHB's hit-delay state).
     */
    public static boolean onBhbPlay(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        BhbBc.LOGGER.info("[bhb_bc] onBhbPlay called: anim={}, player={}", animation != null ? animation.id : "null", playerId);
        if (animation == null || animation.id == null) { BhbBc.LOGGER.warn("[bhb_bc] null animation"); return false; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { BhbBc.LOGGER.warn("[bhb_bc] mc.level null"); return false; }

        String bhbId = animation.id.toString();
        AnimMapping m = MAPPING.get(bhbId);
        if (m == null) { BhbBc.LOGGER.warn("[bhb_bc] no mapping for '{}'", bhbId); return false; }

        net.minecraft.world.entity.player.Player rawPlayer = mc.level.getPlayerByUUID(playerId);
        BhbBc.LOGGER.info("[bhb_bc] player lookup: {} -> {}", playerId, rawPlayer != null ? rawPlayer.getClass().getSimpleName() : "null");
        if (!(rawPlayer instanceof AbstractClientPlayer clientPlayer)) { BhbBc.LOGGER.warn("[bhb_bc] not AbstractClientPlayer"); return false; }
        if (!(clientPlayer instanceof PlayerAttackAnimatable animatable)) { BhbBc.LOGGER.warn("[bhb_bc] not PlayerAttackAnimatable"); return false; }

        BhbBc.LOGGER.info("[bhb_bc] firing BC anim: {} hand={} upswing={}", m.animation(), m.hand(), m.upswing());
        // BHB's speedMultiplier is attackSpeed/1.6 (≈2.5 for a normal player) — a
        // hit-timing scalar, not a BC animation playback rate. BC expects 1.0 = designed speed.
        animatable.playAttackAnimation(m.animation(), m.hand(), 1.0f, m.upswing());
        return true;
    }
}
