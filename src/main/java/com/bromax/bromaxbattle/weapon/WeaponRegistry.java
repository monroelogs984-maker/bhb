package com.bromax.bromaxbattle.weapon;

import com.bromax.bromaxbattle.BromaxBattle;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WeaponRegistry {
    public static final WeaponRegistry INSTANCE = new WeaponRegistry();

    private final Map<String, WeaponAttributes>          itemOverrides       = new HashMap<>();
    private final Map<WeaponCategory, WeaponAttributes>  categoryDefaults    = new EnumMap<>(WeaponCategory.class);
    private final Map<String, WeaponAttributes>          classificationCache = new ConcurrentHashMap<>();

    public void init() {
        loadBuiltinDefaults();
        loadExternalOverrides();
        CategoryAssignments.load();
    }

    private void loadBuiltinDefaults() {
        for (WeaponCategory cat : WeaponCategory.values()) {
            String path = "assets/bromax_battle/weapon_attributes/" + cat.name().toLowerCase() + ".json";
            try (InputStream is = WeaponRegistry.class.getClassLoader().getResourceAsStream(path)) {
                if (is == null) continue;
                JsonObject json = new JsonParser().parse(new InputStreamReader(is)).getAsJsonObject();
                categoryDefaults.put(cat, parseAttributes(json));
            } catch (Exception e) {
                BromaxBattle.LOGGER.error("Failed to load weapon defaults for {}: {}", cat, e.getMessage());
            }
        }
    }

    private void loadExternalOverrides() {
        File overrideDir = new File(Loader.instance().getConfigDir(), "bromax_battle/weapon_attributes");
        if (!overrideDir.exists()) { overrideDir.mkdirs(); return; }
        File[] files = overrideDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                if (!json.has("item")) continue;
                String itemId = json.get("item").getAsString();
                itemOverrides.put(itemId, parseAttributes(json));
                BromaxBattle.LOGGER.info("Loaded weapon override for '{}'", itemId);
            } catch (Exception e) {
                BromaxBattle.LOGGER.error("Failed to load weapon override '{}': {}", file.getName(), e.getMessage());
            }
        }
    }

    public Set<ResourceLocation> getAllAnimationIds() {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (WeaponAttributes attrs : categoryDefaults.values()) {
            for (AttackDefinition a : attrs.attacks) { if (a.animation != null) ids.add(a.animation); }
            if (attrs.idleAnimation != null) ids.add(attrs.idleAnimation);
        }
        for (WeaponAttributes attrs : itemOverrides.values()) {
            for (AttackDefinition a : attrs.attacks) { if (a.animation != null) ids.add(a.animation); }
            if (attrs.idleAnimation != null) ids.add(attrs.idleAnimation);
        }
        return Collections.unmodifiableSet(ids);
    }

    public WeaponAttributes getAttributes(ItemStack stack) {
        if (stack.isEmpty()) {
            return categoryDefaults.getOrDefault(WeaponCategory.GAUNTLETS, null);
        }

        ResourceLocation regName = stack.getItem().getRegistryName();
        String itemKey = regName != null ? regName.toString() : null;

        if (itemKey != null) {
            WeaponAttributes override = itemOverrides.get(itemKey);
            if (override != null) return override;
            WeaponAttributes cached = classificationCache.get(itemKey);
            if (cached != null) return cached;

            // Config category assignment beats the classifier
            WeaponCategory assigned = CategoryAssignments.lookup(itemKey);
            if (assigned != null) {
                WeaponAttributes attrs = categoryDefaults.get(assigned);
                if (attrs != null) {
                    classificationCache.put(itemKey, attrs);
                    return attrs;
                }
            }
        }

        ClassificationResult result = WeaponClassifier.classify(stack);
        WeaponAttributes attrs = null;
        if (result.confidence > 0 && result.category != WeaponCategory.GAUNTLETS) {
            attrs = categoryDefaults.get(result.category);
        }
        if (attrs == null) attrs = categoryDefaults.get(WeaponCategory.GAUNTLETS);

        if (itemKey != null && attrs != null) classificationCache.put(itemKey, attrs);
        return attrs;
    }

    private WeaponAttributes parseAttributes(JsonObject json) {
        if (!json.has("category")) throw new IllegalArgumentException("Missing 'category' field");
        WeaponCategory cat;
        try {
            cat = WeaponCategory.valueOf(json.get("category").getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown category: " + json.get("category").getAsString());
        }

        ResourceLocation idleAnimation = null;
        if (json.has("idle")) {
            idleAnimation = new ResourceLocation(json.get("idle").getAsString());
        }

        List<AttackDefinition> attacks = new ArrayList<>();
        if (!json.has("attacks")) return new WeaponAttributes(cat, attacks, idleAnimation);

        for (JsonElement elem : json.getAsJsonArray("attacks")) {
            try {
                JsonObject a = elem.getAsJsonObject();
                if (!a.has("animation")) continue;
                ResourceLocation animation    = new ResourceLocation(a.get("animation").getAsString());
                int   weight          = a.has("weight")            ? a.get("weight").getAsInt()            : 100;
                float speedMultiplier = a.has("speed_multiplier")  ? a.get("speed_multiplier").getAsFloat() : 1.0f;
                float damageMultiplier= a.has("damage_multiplier") ? a.get("damage_multiplier").getAsFloat(): 1.0f;
                int   hitDelay        = a.has("hit_delay")         ? Math.min(a.get("hit_delay").getAsInt(), 15) : 0;
                attacks.add(new AttackDefinition(animation, weight, speedMultiplier, damageMultiplier, hitDelay));
            } catch (Exception e) {
                BromaxBattle.LOGGER.warn("Skipping malformed attack entry in {}: {}", cat, e.getMessage());
            }
        }

        return new WeaponAttributes(cat, attacks, idleAnimation);
    }
}
