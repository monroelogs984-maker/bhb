package com.bromax.bromaxbattle.client.preview;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Animation preview tool.
 *
 * In game: {@code /bhbpreview [filter]} renders a contact sheet for every animation whose id
 * contains the filter (comma-separated list allowed; all when omitted) into screenshots/bhb_preview/.
 * {@code /bhbgrips <filter>} renders each animation's strike pose with every grip preset side by side.
 *
 * Unattended: launch with {@code -Dbhb.preview=<filter|all>} (add {@code -Dbhb.preview.grips=true}
 * for grip sheets) and it opens or creates a flat creative world called "bhb_preview", renders the
 * sheets, then quits. {@code -Dbhb.dmgtest="<attacks> [item]"} runs /bhbdmgtest there instead
 * (needs -Dbhb.debug=true).
 */
@SideOnly(Side.CLIENT)
public final class AnimationPreview {
    private static final String WORLD = "bhb_preview";
    private static final String AUTO_FILTER = System.getProperty("bhb.preview", "");
    private static final boolean AUTO_GRIPS = Boolean.getBoolean("bhb.preview.grips");
    private static final String AUTO_DMGTEST = System.getProperty("bhb.dmgtest", "");

    private boolean autoStarted = false;
    private boolean autoOpened = false;
    private int ticksInWorld = 0;
    private int quitAtTick = -1;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new AnimationPreview());
        ClientCommandHandler.instance.registerCommand(new PreviewCommand("bhbpreview", false));
        ClientCommandHandler.instance.registerCommand(new PreviewCommand("bhbgrips", true));
    }

    private static boolean autoMode() {
        return !AUTO_FILTER.isEmpty() || !AUTO_DMGTEST.isEmpty();
    }

    public static List<ResourceLocation> select(String filter) {
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        boolean all = f.isEmpty() || f.equals("all");
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : WeaponRegistry.INSTANCE.getAllAnimationIds()) {
            if (AnimationRegistry.INSTANCE.get(id) == null) continue;
            if (all) { out.add(id); continue; }
            for (String part : f.split(",")) {
                if (!part.trim().isEmpty() && id.toString().contains(part.trim())) { out.add(id); break; }
            }
        }
        out.sort((a, b) -> a.toString().compareTo(b.toString()));
        return out;
    }

    private static final class PreviewCommand extends CommandBase {
        private final String name;
        private final boolean grips;

        PreviewCommand(String name, boolean grips) {
            this.name = name;
            this.grips = grips;
        }

        @Override public String getName() { return name; }
        @Override public String getUsage(ICommandSender sender) { return "/" + name + " [filter]"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean checkPermission(MinecraftServer server, ICommandSender sender) { return true; }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
            String filter = String.join(" ", args);
            List<ResourceLocation> jobs = select(filter);
            if (jobs.isEmpty()) {
                sender.sendMessage(new TextComponentString("[BHB] No animations match '" + filter + "'"));
                return;
            }
            sender.sendMessage(new TextComponentString("[BHB] Rendering " + jobs.size() + " preview sheets to screenshots/bhb_preview"));
            Minecraft mc = Minecraft.getMinecraft();
            // Let the chat screen close first
            mc.addScheduledTask(() -> mc.displayGuiScreen(new AnimationPreviewScreen(jobs, null, grips)));
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!autoMode() || autoStarted || !(event.getGui() instanceof GuiMainMenu)) return;
        autoStarted = true;
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.getSaveLoader().canLoadWorld(WORLD)) {
                mc.launchIntegratedServer(WORLD, WORLD, null);
            } else {
                WorldSettings settings = new WorldSettings(0L, GameType.CREATIVE, false, false, WorldType.FLAT);
                settings.enableCommands();
                mc.launchIntegratedServer(WORLD, WORLD, settings);
            }
        });
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !autoMode()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (quitAtTick >= 0 && ++ticksInWorld >= quitAtTick) { mc.shutdown(); return; }
        if (autoOpened) return;
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (++ticksInWorld < 40) return;
        autoOpened = true;
        if (!AUTO_DMGTEST.isEmpty()) {
            int attacks = Integer.parseInt(AUTO_DMGTEST.trim().split("\\s+")[0]);
            mc.player.sendChatMessage("/bhbdmgtest " + AUTO_DMGTEST.trim());
            quitAtTick = ticksInWorld + 120 + attacks * 30;
            return;
        }
        List<ResourceLocation> jobs = select(AUTO_FILTER);
        BromaxBattle.LOGGER.info("[BHB] Animation preview (auto): {} animations match '{}'", jobs.size(), AUTO_FILTER);
        mc.displayGuiScreen(new AnimationPreviewScreen(jobs, mc::shutdown, AUTO_GRIPS));
    }
}
