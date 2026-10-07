package com.bromax.bromaxbattle.weapon;

import com.bromax.bromaxbattle.BromaxBattle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.common.Loader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * User-editable category assignments: config/bromax_battle/category_assignments.json
 *
 * Maps items to BHB weapon categories, overriding the automatic classifier.
 * Two forms: exact registry IDs and keyword substrings. The assigned item
 * inherits the full category moveset (animations, variants, AOE) — this is
 * the lightweight alternative to a full weapon_attributes/ override file.
 */
public class CategoryAssignments {

    private static final Map<String, WeaponCategory> itemAssignments    = new LinkedHashMap<>();
    private static final Map<String, WeaponCategory> keywordAssignments = new LinkedHashMap<>();

    public static void load() {
        itemAssignments.clear();
        keywordAssignments.clear();

        File dir  = new File(Loader.instance().getConfigDir(), "bromax_battle");
        File file = new File(dir, "category_assignments.json");
        if (!file.exists()) {
            writeDefault(dir, file);
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            readSection(json, "items",    itemAssignments);
            readSection(json, "keywords", keywordAssignments);
            if (!itemAssignments.isEmpty() || !keywordAssignments.isEmpty()) {
                BromaxBattle.LOGGER.info("Loaded {} item and {} keyword category assignments",
                        itemAssignments.size(), keywordAssignments.size());
            }
        } catch (Exception e) {
            BromaxBattle.LOGGER.error("Failed to load category_assignments.json: {}", e.getMessage());
        }
    }

    private static void readSection(JsonObject json, String key, Map<String, WeaponCategory> target) {
        if (!json.has(key) || !json.get(key).isJsonObject()) return;
        for (Map.Entry<String, com.google.gson.JsonElement> entry : json.getAsJsonObject(key).entrySet()) {
            String catName = entry.getValue().getAsString().toUpperCase(Locale.ROOT);
            try {
                target.put(entry.getKey().toLowerCase(Locale.ROOT), WeaponCategory.valueOf(catName));
            } catch (IllegalArgumentException e) {
                BromaxBattle.LOGGER.warn(
                        "category_assignments.json: '{}' -> '{}' skipped — unknown category. "
                        + "Check _valid_categories in the file for the allowed names.",
                        entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    /** Exact registry ID match first, then keyword substring match against the
     *  full "modid:path" ID. Returns null if nothing assigned. */
    public static WeaponCategory lookup(String registryId) {
        if (registryId == null) return null;
        String id = registryId.toLowerCase(Locale.ROOT);

        WeaponCategory exact = itemAssignments.get(id);
        if (exact != null) return exact;

        for (Map.Entry<String, WeaponCategory> entry : keywordAssignments.entrySet()) {
            if (id.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static void writeDefault(File dir, File file) {
        try {
            if (!dir.exists()) dir.mkdirs();

            JsonObject root = new JsonObject();

            JsonArray about = new JsonArray();
            about.add(new com.google.gson.JsonPrimitive("Assigns items to BHB weapon categories, overriding automatic classification."));
            about.add(new com.google.gson.JsonPrimitive("Use this when a modded weapon gets the wrong moveset - for example, a big"));
            about.add(new com.google.gson.JsonPrimitive("two-handed greatsword that extends the vanilla sword class and therefore"));
            about.add(new com.google.gson.JsonPrimitive("swings like a shortsword. The assigned item inherits the full moveset of"));
            about.add(new com.google.gson.JsonPrimitive("its category: animations, attack variants, speed/damage deviation, and AOE."));
            about.add(new com.google.gson.JsonPrimitive("To give an item fully custom attacks instead, create a file in the"));
            about.add(new com.google.gson.JsonPrimitive("weapon_attributes/ folder next to this one."));
            root.add("_about", about);

            JsonArray usage = new JsonArray();
            usage.add(new com.google.gson.JsonPrimitive("'items' maps exact registry IDs to a category:"));
            usage.add("    \"simplyswords:runic_greatblade\": \"greatsword\"");
            usage.add(new com.google.gson.JsonPrimitive("'keywords' maps substrings to a category - matched against the full"));
            usage.add(new com.google.gson.JsonPrimitive("registry ID (modid:item_name) of every item, so they can catch a whole"));
            usage.add(new com.google.gson.JsonPrimitive("family of weapons or scope to one mod:"));
            usage.add("    \"claymore\": \"greatsword\"          (any item with 'claymore' in its name)");
            usage.add("    \"epicknights:halberd\": \"halberd\"  (one mod's halberds only)");
            usage.add(new com.google.gson.JsonPrimitive("Exact 'items' entries always win over 'keywords'. Matching is"));
            usage.add(new com.google.gson.JsonPrimitive("case-insensitive. Changes require a game restart."));
            usage.add(new com.google.gson.JsonPrimitive("Find an item's registry ID in-game with advanced tooltips (F3+H)."));
            root.add("_usage", usage);

            JsonArray categories = new JsonArray();
            for (WeaponCategory cat : WeaponCategory.values()) {
                categories.add(new com.google.gson.JsonPrimitive(cat.name().toLowerCase(Locale.ROOT)));
            }
            root.add("_valid_categories", categories);

            root.add("items",    new JsonObject());
            root.add("keywords", new JsonObject());

            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(root, writer);
            }
            BromaxBattle.LOGGER.info("Created default category_assignments.json");
        } catch (Exception e) {
            BromaxBattle.LOGGER.error("Failed to write default category_assignments.json: {}", e.getMessage());
        }
    }
}
