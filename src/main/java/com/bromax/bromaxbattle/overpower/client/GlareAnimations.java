package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.BromaxBattle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Plays the Glare Strike pose and, after the pose, the thrust, through BHB's animation engine. */
@OnlyIn(Dist.CLIENT)
public class GlareAnimations {
    private static AnimationDefinition pose, thrust;
    private static final List<long[]> PENDING = new ArrayList<>(); // {entityId, playAtTick}

    public static void load() {
        pose = read("glare_pose");
        thrust = read("glare_thrust");
    }

    private static AnimationDefinition read(String name) {
        String path = "assets/" + BromaxBattle.MOD_ID + "/animations/" + name + ".json";
        try (InputStream is = GlareAnimations.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            JsonObject json = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            return AnimationDefinition.fromJson(ResourceLocation.fromNamespaceAndPath(BromaxBattle.MOD_ID, name), json);
        } catch (Exception e) {
            BromaxBattle.LOGGER.error("[Overpower] Failed to load animation {}: {}", name, e.getMessage());
            return null;
        }
    }

    public static void play(int entityId, int poseTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e == null) return;
        if (pose != null) AnimationController.INSTANCE.play(e.getUUID(), pose, 1f);
        PENDING.add(new long[]{entityId, mc.level.getGameTime() + poseTicks});
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { PENDING.clear(); return; }
        long now = mc.level.getGameTime();
        for (Iterator<long[]> it = PENDING.iterator(); it.hasNext(); ) {
            long[] p = it.next();
            if (now < p[1]) continue;
            it.remove();
            Entity e = mc.level.getEntity((int) p[0]);
            if (e != null && thrust != null) AnimationController.INSTANCE.play(e.getUUID(), thrust, 1f);
        }
    }
}
