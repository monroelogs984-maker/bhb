package com.bromax.bromaxbattle.overpower.profile;

import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxbattle.BromaxBattle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Overpower/Glare profiles per BHB weapon category. Defaults ship in
 * data/bromax_battle/overpower/weapon_profiles.json; a copy at
 * config/bromax_battle/overpower_profiles.json replaces them (same format) so a pack can rebalance
 * without rebuilding.
 */
public final class ProfileRegistry {
    private static final String BUILTIN = "data/bromax_battle/overpower/weapon_profiles.json";
    private static final Map<WeaponCategory, WeaponProfile> PROFILES = new EnumMap<>(WeaponCategory.class);
    private static float damagePerLevel = 0.015f;

    private ProfileRegistry() {}

    public static void load() {
        PROFILES.clear();
        File override = FMLPaths.CONFIGDIR.get().resolve("bromax_battle/overpower_profiles.json").toFile();
        try (Reader reader = override.exists() ? new FileReader(override) : builtin()) {
            if (reader == null) return;
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("damage_per_level")) damagePerLevel = json.get("damage_per_level").getAsFloat();
            JsonObject cats = json.getAsJsonObject("categories");
            for (Map.Entry<String, com.google.gson.JsonElement> e : cats.entrySet()) {
                WeaponCategory cat;
                try {
                    cat = WeaponCategory.valueOf(e.getKey().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    BromaxBattle.LOGGER.warn("[Overpower] weapon_profiles: unknown category '{}'", e.getKey());
                    continue;
                }
                JsonObject p = e.getValue().getAsJsonObject();
                PROFILES.put(cat, new WeaponProfile(p.get("overpower").getAsInt(), p.get("glare").getAsInt()));
            }
            BromaxBattle.LOGGER.info("[Overpower] Loaded {} weapon profiles{}", PROFILES.size(),
                    override.exists() ? " (config override)" : "");
        } catch (Exception e) {
            BromaxBattle.LOGGER.error("[Overpower] Failed to load weapon profiles: {}", e.getMessage());
        }
    }

    private static Reader builtin() {
        InputStream is = ProfileRegistry.class.getClassLoader().getResourceAsStream(BUILTIN);
        return is == null ? null : new InputStreamReader(is);
    }

    public static float damagePerLevel() {
        return damagePerLevel;
    }

    public static WeaponProfile get(WeaponCategory category) {
        return category == null ? WeaponProfile.NEUTRAL : PROFILES.getOrDefault(category, WeaponProfile.NEUTRAL);
    }

    /** Profile of a held item, or null when BHB doesn't treat it as a weapon. */
    public static WeaponProfile of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(stack);
        return attrs == null ? null : get(attrs.category);
    }
}
