package com.bromax.bromaxbattle.overpower.registry;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.overpower.combat.OverpowerData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.function.Supplier;

public final class OpRegistries {
    private OpRegistries() {}

    public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, BromaxBattle.MOD_ID);
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, BromaxBattle.MOD_ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BromaxBattle.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BromaxBattle.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BromaxBattle.MOD_ID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, BromaxBattle.MOD_ID);

    /** Multiplies the Overpower pressure this entity's hits deal. */
    public static final DeferredHolder<Attribute, Attribute> OVERPOWER_POWER = attribute("overpower_power", 1.0, 0.0, 10.0);
    /** Divides the Overpower pressure this entity takes. */
    public static final DeferredHolder<Attribute, Attribute> OVERPOWER_RESISTANCE = attribute("overpower_resistance", 1.0, 0.1, 10.0);
    /** Multiplies the Glare Strike window. */
    public static final DeferredHolder<Attribute, Attribute> GLARE_WINDOW = attribute("glare_window", 1.0, 0.0, 10.0);
    /** Multiplies the Glare Strike thrust damage. */
    public static final DeferredHolder<Attribute, Attribute> GLARE_DAMAGE = attribute("glare_damage", 1.0, 0.0, 10.0);

    public static final DeferredHolder<MobEffect, MobEffect> OVERPOWERED = EFFECTS.register("overpowered", () ->
            new OpEffect(MobEffectCategory.HARMFUL, 0x8B1A1A)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, id("overpowered_slow"), -0.35, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                    .addAttributeModifier(Attributes.ATTACK_DAMAGE, id("overpowered_weak"), -0.15, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    /** Bosses resist the slowness: the disadvantage only. */
    public static final DeferredHolder<MobEffect, MobEffect> EXPOSED = EFFECTS.register("exposed", () ->
            new OpEffect(MobEffectCategory.HARMFUL, 0xB03030)
                    .addAttributeModifier(Attributes.ATTACK_DAMAGE, id("exposed_weak"), -0.15, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    /** Can't move or deal damage. */
    public static final DeferredHolder<MobEffect, MobEffect> STAGGERED = EFFECTS.register("staggered", () ->
            new OpEffect(MobEffectCategory.HARMFUL, 0xD0B060)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, id("staggered_slow"), -0.95, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    /** The Glare Strike pose: takes reduced damage. */
    public static final DeferredHolder<MobEffect, MobEffect> GLARING = EFFECTS.register("glaring", () ->
            new OpEffect(MobEffectCategory.BENEFICIAL, 0xFFC040));

    public static final net.neoforged.neoforge.registries.DeferredBlock<com.bromax.bromaxbattle.overpower.bench.BenchBlock> BENCH =
            BLOCKS.register("weaponsmith_bench", () -> new com.bromax.bromaxbattle.overpower.bench.BenchBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                            .mapColor(net.minecraft.world.level.material.MapColor.WOOD)
                            .strength(2.5f).sound(net.minecraft.world.level.block.SoundType.WOOD).noOcclusion()));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> BENCH_ITEM =
            ITEMS.registerSimpleBlockItem("weaponsmith_bench", BENCH);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<com.bromax.bromaxbattle.overpower.bench.BenchBlockEntity>> BENCH_BE =
            BLOCK_ENTITIES.register("weaponsmith_bench", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of(com.bromax.bromaxbattle.overpower.bench.BenchBlockEntity::new, BENCH.get()).build(null));

    public static final Supplier<AttachmentType<OverpowerData>> DATA = ATTACHMENTS.register("overpower",
            () -> AttachmentType.builder(OverpowerData::new).build());

    private static DeferredHolder<Attribute, Attribute> attribute(String name, double def, double min, double max) {
        return ATTRIBUTES.register(name, () -> new RangedAttribute(
                "attribute.name." + BromaxBattle.MOD_ID + "." + name, def, min, max).setSyncable(true));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BromaxBattle.MOD_ID, path);
    }

    public static void register(IEventBus modBus) {
        ATTRIBUTES.register(modBus);
        EFFECTS.register(modBus);
        ATTACHMENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(OpRegistries::addAttributes);
        modBus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(BENCH_ITEM);
        });
    }

    /** Every living entity gets the four Overpower attributes. */
    private static void addAttributes(EntityAttributeModificationEvent event) {
        List<DeferredHolder<Attribute, Attribute>> all = List.of(OVERPOWER_POWER, OVERPOWER_RESISTANCE, GLARE_WINDOW, GLARE_DAMAGE);
        for (EntityType<? extends net.minecraft.world.entity.LivingEntity> type : event.getTypes()) {
            for (DeferredHolder<Attribute, Attribute> attr : all) {
                if (!event.has(type, attr)) event.add(type, attr);
            }
        }
    }

    private static final class OpEffect extends MobEffect {
        OpEffect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }
}
