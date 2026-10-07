package com.bromax.bromaxbattle.animation;

import com.bromax.bromaxbattle.BromaxBattle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public class AnimationRegistry {
    public static final AnimationRegistry INSTANCE = new AnimationRegistry();

    private final Map<ResourceLocation, AnimationDefinition> registry = new HashMap<>();

    /** Animations no weapon file references: the guard poses. Loaded alongside the weapons' own. */
    public static final ResourceLocation GUARD_ONE_HANDED =
            ResourceLocation.fromNamespaceAndPath(BromaxBattle.MOD_ID, "guard_one_handed");
    public static final ResourceLocation GUARD_TWO_HANDED =
            ResourceLocation.fromNamespaceAndPath(BromaxBattle.MOD_ID, "guard_two_handed");
    public static final java.util.List<ResourceLocation> EXTRA_IDS = java.util.List.of(GUARD_ONE_HANDED, GUARD_TWO_HANDED);

    public void load(Set<ResourceLocation> weaponIds) {
        registry.clear();
        Set<ResourceLocation> ids = new java.util.HashSet<>(weaponIds);
        ids.addAll(EXTRA_IDS);
        for (ResourceLocation id : ids) {
            // Strip namespace prefix from path component — IDs are stored as "bromax_battle:shortsword_default"
            // but the file lives at assets/bromax_battle/animations/shortsword_default.json
            String path = "assets/" + id.getNamespace() + "/animations/" + id.getPath() + ".json";
            try (InputStream is = AnimationRegistry.class.getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    BromaxBattle.LOGGER.warn("[BHB] Animation file not found: {}", path);
                    continue;
                }
                JsonObject json = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                registry.put(id, AnimationDefinition.fromJson(id, json));
            } catch (Exception e) {
                BromaxBattle.LOGGER.error("[BHB] Failed to parse animation {}: {}", id, e.getMessage());
            }
        }
        BromaxBattle.LOGGER.info("[BHB] Loaded {} animations", registry.size());
    }

    public AnimationDefinition get(ResourceLocation id) {
        return registry.get(id);
    }
}
