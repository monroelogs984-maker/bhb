package com.bromax.bromaxbattle.animation;

import com.bromax.bromaxbattle.BromaxBattle;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@OnlyIn(Dist.CLIENT)
public class AnimationController {
    public static final AnimationController INSTANCE = new AnimationController();

    private final Map<UUID, AnimationState> states        = new ConcurrentHashMap<>();
    private final Map<UUID, AnimationState> offhandStates = new ConcurrentHashMap<>();
    private final Map<UUID, AnimationState> idleStates    = new ConcurrentHashMap<>();
    private final AtomicBoolean             mixinConfirmed = new AtomicBoolean(false);

    private final Map<UUID, Long>  lastInterpNanos   = new ConcurrentHashMap<>();
    private final Map<UUID, Float> cachedPartialTick = new ConcurrentHashMap<>();
    private static final long INTERP_INTERVAL_NS = 1_000_000_000L / 40;

    private final float[] EVAL_SCRATCH   = new float[3];
    private final float[] INTERP_SCRATCH = new float[3];
    private final float[] TRANS_SCRATCH  = new float[3];

    private static final float LUNGE_BODY_RX      = (float) Math.toRadians( 55f);
    private static final float LUNGE_ARM_RX       = (float) Math.toRadians(-45f);
    private static final float LUNGE_FRONT_LEG_RX = (float) Math.toRadians(-55f);
    private static final float LUNGE_BACK_LEG_RX  = (float) Math.toRadians( 65f);
    private static final float LUNGE_OFFARM_RX    = (float) Math.toRadians( 18f);
    public  static final float LUNGE_VELOCITY_SCALE = 0.30f;

    // HumanoidModel.setupAnim applies: rightArm.xRot = rightArm.xRot * 0.5 - PI/10 for ITEM pose
    private static final float VANILLA_RIGHT_ARM_RX = -(float)(Math.PI / 10.0);

    private static final float[] VANILLA_RP_X = new float[BoneTarget.COUNT];
    private static final float[] VANILLA_RP_Y = new float[BoneTarget.COUNT];
    private static final float[] VANILLA_RP_Z = new float[BoneTarget.COUNT];
    static {
        VANILLA_RP_X[BoneTarget.RIGHT_ARM.ordinal()] = -5f;   VANILLA_RP_Y[BoneTarget.RIGHT_ARM.ordinal()] = 2f;
        VANILLA_RP_X[BoneTarget.LEFT_ARM.ordinal()]  =  5f;   VANILLA_RP_Y[BoneTarget.LEFT_ARM.ordinal()]  = 2f;
        VANILLA_RP_X[BoneTarget.RIGHT_LEG.ordinal()] = -1.9f; VANILLA_RP_Y[BoneTarget.RIGHT_LEG.ordinal()] = 12f;
        VANILLA_RP_X[BoneTarget.LEFT_LEG.ordinal()]  =  1.9f; VANILLA_RP_Y[BoneTarget.LEFT_LEG.ordinal()]  = 12f;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void play(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        if (playerId == null || animation == null) return;
        if (!Float.isFinite(speedMultiplier) || speedMultiplier < 0.1f) speedMultiplier = 0.1f;
        if (speedMultiplier > 5.0f) speedMultiplier = 5.0f;

        AnimationState prev = states.get(playerId);
        float[][] snapshot = null;
        if (prev != null) {
            float prevTick = Math.min(prev.tickF, prev.animation.duration);
            snapshot = new float[BoneTarget.COUNT][];
            for (BoneTarget bone : prev.animation.blendMask) {
                prev.animation.evaluate(bone, prevTick, EVAL_SCRATCH);
                float blend = prev.getBlendFactor(prevTick);
                snapshot[bone.ordinal()] = new float[]{
                    EVAL_SCRATCH[0] * blend, EVAL_SCRATCH[1] * blend, EVAL_SCRATCH[2] * blend
                };
            }
        }
        states.put(playerId, new AnimationState(animation, speedMultiplier, snapshot));
    }

    public void playOffhand(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        if (playerId == null || animation == null) return;
        if (!Float.isFinite(speedMultiplier) || speedMultiplier < 0.1f) speedMultiplier = 0.1f;
        if (speedMultiplier > 5.0f) speedMultiplier = 5.0f;
        offhandStates.put(playerId, new AnimationState(animation, speedMultiplier, null));
    }

    public void playIdleIfInactive(UUID playerId, AnimationDefinition animation) {
        if (playerId == null || animation == null) return;
        if (idleStates.containsKey(playerId)) return;
        idleStates.put(playerId, new AnimationState(animation, 1.0f, null));
    }

    public void clearIdle(UUID playerId) {
        idleStates.remove(playerId);
    }

    public boolean isPlaying(UUID playerId) {
        AnimationState s = states.get(playerId);
        return s != null && !s.isMainBodyComplete();
    }

    public boolean isOffhandPlaying(UUID playerId) {
        AnimationState s = offhandStates.get(playerId);
        return s != null && !s.isMainBodyComplete();
    }

    public boolean hasActiveState(UUID playerId) {
        return states.containsKey(playerId) || offhandStates.containsKey(playerId) || idleStates.containsKey(playerId);
    }

    public boolean isHitWindowActive(UUID playerId) {
        AnimationState s = states.get(playerId);
        return s != null && s.animation.isHitWindowActive(s.tickF);
    }

    public float getAttackSwingProgress(UUID playerId) {
        AnimationState s = states.get(playerId);
        if (s == null) return 0f;
        float d = s.animation.duration;
        return d <= 0 ? 0f : Math.min(1f, s.tickF / d);
    }

    public float consumeLungeImpulse(UUID playerId) {
        AnimationState s = states.get(playerId);
        if (s == null || s.lungeImpulseFired || s.animation.lunge <= 0f) return 0f;
        s.lungeImpulseFired = true;
        return s.animation.lunge;
    }

    /**
     * Returns interpolated [rx, ry, rz] for the RIGHT_ARM or LEFT_ARM bone, or null if none active.
     * Called by MixinItemInHandRenderer to animate the first-person arm.
     * Does NOT include the VANILLA_RIGHT_ARM_RX offset — that's third-person only.
     */
    public float[] getArmAngles(UUID playerId, boolean mainhand, float partialTick) {
        AnimationState state = mainhand ? states.get(playerId) : offhandStates.get(playerId);
        if (state == null || state.cachedDeltas == null) return null;

        BoneTarget bone = mainhand ? BoneTarget.RIGHT_ARM : BoneTarget.LEFT_ARM;
        int ord = bone.ordinal();
        float[] cur = state.cachedDeltas[ord];
        if (cur == null) return null;

        if (!Float.isFinite(partialTick)) partialTick = 1f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));

        float[] prev = state.prevCachedDeltas != null ? state.prevCachedDeltas[ord] : null;
        if (prev != null) {
            return new float[]{
                prev[0] + (cur[0] - prev[0]) * partialTick,
                prev[1] + (cur[1] - prev[1]) * partialTick,
                prev[2] + (cur[2] - prev[2]) * partialTick
            };
        }
        return new float[]{cur[0], cur[1], cur[2]};
    }

    /**
     * Returns interpolated [rx, ry, rz] for the WEAPON_HAND bone, or null if none active.
     * Called by MixinItemInHandLayer to rotate the held item GL matrix.
     */
    public float[] getWeaponAngles(UUID playerId, boolean mainhand, float partialTick) {
        AnimationState state = mainhand ? states.get(playerId) : offhandStates.get(playerId);
        if (state == null || state.cachedDeltas == null) return null;

        int ord = BoneTarget.WEAPON_HAND.ordinal();
        float[] cur = state.cachedDeltas[ord];
        if (cur == null) return null;

        if (!Float.isFinite(partialTick)) partialTick = 1f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));

        float[] prev = state.prevCachedDeltas != null ? state.prevCachedDeltas[ord] : null;
        // WEAPON_HAND values in the animation JSONs were authored without live testing
        // and are too large — scale down to keep rotation subtle
        final float WEAPON_SCALE = 0.25f;
        if (prev != null) {
            return new float[]{
                (prev[0] + (cur[0] - prev[0]) * partialTick) * WEAPON_SCALE,
                (prev[1] + (cur[1] - prev[1]) * partialTick) * WEAPON_SCALE,
                (prev[2] + (cur[2] - prev[2]) * partialTick) * WEAPON_SCALE
            };
        }
        return new float[]{cur[0] * WEAPON_SCALE, cur[1] * WEAPON_SCALE, cur[2] * WEAPON_SCALE};
    }

    /** Advances all animation states by one game tick. Called from ClientSetup.onClientTick. */
    public void tickAll() {
        tickMap(states);
        tickMap(offhandStates);
        tickMap(idleStates);
    }

    private static void tickMap(Map<UUID, AnimationState> map) {
        map.entrySet().removeIf(entry -> {
            AnimationState state = entry.getValue();
            state.tickF += state.speedMultiplier;
            if (state.isComplete()) return true;
            if (state.tickF > state.animation.duration * 4f) return true;
            state.refreshCache();
            return false;
        });
    }

    public void clear(UUID playerId) {
        states.remove(playerId);
        offhandStates.remove(playerId);
        idleStates.remove(playerId);
        lastInterpNanos.remove(playerId);
        cachedPartialTick.remove(playerId);
    }

    // -------------------------------------------------------------------------
    // Model application — called from MixinPlayerModel every render frame
    // -------------------------------------------------------------------------

    public void applyToModel(UUID playerId, HumanoidModel<?> model, float partialTick) {
        if (mixinConfirmed.compareAndSet(false, true)) {
            BromaxBattle.LOGGER.info("[BHB] PlayerModel mixin confirmed — animation engine active.");
        }

        AnimationState mainhand = states.get(playerId);
        AnimationState offhand  = offhandStates.get(playerId);

        if (!Float.isFinite(partialTick)) partialTick = 1.0f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));

        long now    = System.nanoTime();
        Long lastNs = lastInterpNanos.get(playerId);
        if (lastNs == null || (now - lastNs) >= INTERP_INTERVAL_NS) {
            lastInterpNanos.put(playerId, now);
            cachedPartialTick.put(playerId, partialTick);
        } else {
            partialTick = cachedPartialTick.getOrDefault(playerId, partialTick);
        }

        AnimationState idle = idleStates.get(playerId);

        try {
            if (mainhand == null && offhand == null && idle != null && idle.cachedDeltas != null) {
                applyToModelUnsafe(idle, model, partialTick, false, false, true);
            }
            if (mainhand != null && mainhand.cachedDeltas != null) {
                applyToModelUnsafe(mainhand, model, partialTick, false, false, false);
            }
            if (offhand != null && offhand.cachedDeltas != null) {
                applyToModelUnsafe(offhand, model, partialTick, true, mainhand != null, false);
            }
        } catch (Exception e) {
            BromaxBattle.LOGGER.error("[BHB] Animation render error for {}, clearing: {}", playerId, e.getMessage());
            states.remove(playerId);
            offhandStates.remove(playerId);
            idleStates.remove(playerId);
        }
    }

    private void applyToModelUnsafe(AnimationState state, HumanoidModel<?> model,
                                     float partialTick, boolean mirror, boolean skipBody,
                                     boolean skipHeadLock) {
        if (state.cachedDeltas == null) return;

        boolean isPlayerModel = model instanceof PlayerModel;

        // Head locking
        if (!skipBody && !skipHeadLock) {
            ModelPart head = model.head;
            ModelPart hat  = isPlayerModel ? ((PlayerModel<?>) model).hat : null;
            if (head != null) {
                if (Float.isNaN(state.lockedHeadRX)) {
                    state.lockedHeadRX = head.xRot;
                    state.lockedHeadRY = head.yRot;
                }
                head.xRot = state.lockedHeadRX;
                head.yRot = state.lockedHeadRY;
                if (hat != null) { hat.xRot = state.lockedHeadRX; hat.yRot = state.lockedHeadRY; }
            }
        }

        for (BoneTarget bone : state.animation.blendMask) {
            if (skipBody && bone != BoneTarget.RIGHT_ARM && bone != BoneTarget.LEFT_ARM) continue;

            float[] cur = state.cachedDeltas[bone.ordinal()];
            if (cur == null) continue;
            float[] prev = state.prevCachedDeltas != null ? state.prevCachedDeltas[bone.ordinal()] : null;
            final float[] c;
            if (prev != null) {
                INTERP_SCRATCH[0] = prev[0] + (cur[0] - prev[0]) * partialTick;
                INTERP_SCRATCH[1] = prev[1] + (cur[1] - prev[1]) * partialTick;
                INTERP_SCRATCH[2] = prev[2] + (cur[2] - prev[2]) * partialTick;
                c = INTERP_SCRATCH;
            } else {
                c = cur;
            }

            ModelPart main = getMainPart(model, bone, mirror);
            ModelPart wear = isPlayerModel ? getWearPart((PlayerModel<?>) model, bone, mirror) : null;

            switch (bone) {
                case RIGHT_LEG, LEFT_LEG -> {
                    setPart(main, c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setPart(wear, c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                }
                case RIGHT_ARM -> {
                    setPart(main, VANILLA_RIGHT_ARM_RX - c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setPart(wear, VANILLA_RIGHT_ARM_RX - c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                }
                case LEFT_ARM -> {
                    setPart(main, -c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setPart(wear, -c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                }
                case BODY -> {
                    if (Float.isNaN(state.lockedBodyRY) && main != null) {
                        state.lockedBodyRY = main.yRot;
                    }
                    float bodyRY = Float.isNaN(state.lockedBodyRY) ? 0f : state.lockedBodyRY;
                    setBodyLean(main, c[0], bodyRY, mirror ? -c[2] : c[2]);
                    setBodyLean(wear, c[0], bodyRY, mirror ? -c[2] : c[2]);
                }
                default -> {
                    addDelta(main, c[0], c[1], c[2]);
                    addDelta(wear, c[0], c[1], c[2]);
                }
            }
        }

        // Lunge visual
        if (!skipHeadLock && state.animation.lunge > 0f) {
            float d = state.animation.duration;
            float swingProg = d > 0 ? Math.min(1f, state.tickF / d) : 0f;
            float luCurve = (float) Math.sin(Math.PI * swingProg);
            float lu = state.animation.lunge * luCurve * state.getBlendFactor(state.tickF);

            if (lu > 0.001f) {
                ModelPart body = model.body;
                if (body != null) body.xRot += lu * LUNGE_BODY_RX;

                ModelPart strikeArm = mirror ? model.leftArm  : model.rightArm;
                if (strikeArm != null) strikeArm.xRot += lu * LUNGE_ARM_RX;

                if (!skipBody) {
                    ModelPart frontLeg = mirror ? model.leftLeg  : model.rightLeg;
                    ModelPart backLeg  = mirror ? model.rightLeg : model.leftLeg;
                    if (frontLeg != null) frontLeg.xRot += lu * LUNGE_FRONT_LEG_RX;
                    if (backLeg  != null) backLeg.xRot  += lu * LUNGE_BACK_LEG_RX;
                }

                ModelPart offArm = mirror ? model.rightArm : model.leftArm;
                if (offArm != null) offArm.xRot += lu * LUNGE_OFFARM_RX;
            }
        }

        // Bone translations
        if (state.animation.hasTranslations && state.cachedTranslations != null) {
            for (BoneTarget bone : state.animation.blendMask) {
                if (skipBody && bone != BoneTarget.RIGHT_ARM && bone != BoneTarget.LEFT_ARM) continue;
                if (bone == BoneTarget.WEAPON_HAND) continue;

                float[] tCur = state.cachedTranslations[bone.ordinal()];
                if (tCur == null) continue;
                float[] tPrev = state.prevCachedTranslations != null
                    ? state.prevCachedTranslations[bone.ordinal()] : null;
                if (tPrev != null) {
                    TRANS_SCRATCH[0] = tPrev[0] + (tCur[0] - tPrev[0]) * partialTick;
                    TRANS_SCRATCH[1] = tPrev[1] + (tCur[1] - tPrev[1]) * partialTick;
                    TRANS_SCRATCH[2] = tPrev[2] + (tCur[2] - tPrev[2]) * partialTick;
                } else {
                    TRANS_SCRATCH[0] = tCur[0]; TRANS_SCRATCH[1] = tCur[1]; TRANS_SCRATCH[2] = tCur[2];
                }

                int ord = bone.ordinal();
                ModelPart main = getMainPart(model, bone, mirror);
                ModelPart wear = isPlayerModel ? getWearPart((PlayerModel<?>) model, bone, mirror) : null;

                float sign = mirror ? -1f : 1f;
                float rpX  = sign * (VANILLA_RP_X[ord] + TRANS_SCRATCH[0]);
                float rpY  = VANILLA_RP_Y[ord] + TRANS_SCRATCH[1];
                float rpZ  = VANILLA_RP_Z[ord] + TRANS_SCRATCH[2];
                applyTranslation(main, rpX, rpY, rpZ);
                applyTranslation(wear, rpX, rpY, rpZ);
            }
        }
    }

    // -------------------------------------------------------------------------
    // ModelPart helpers — 1.19.2 API (xRot/yRot/zRot, x/y/z)
    // -------------------------------------------------------------------------

    private static void setPart(ModelPart p, float rx, float ry, float rz) {
        if (p == null) return;
        p.xRot = rx; p.yRot = ry; p.zRot = rz;
    }

    private static void setBodyLean(ModelPart p, float rx, float ry, float rz) {
        if (p == null) return;
        p.xRot = rx; p.yRot = ry; p.zRot = rz;
    }

    private static void addDelta(ModelPart p, float dx, float dy, float dz) {
        if (p == null) return;
        p.xRot += dx; p.yRot += dy; p.zRot += dz;
    }

    private static void applyTranslation(ModelPart p, float x, float y, float z) {
        if (p == null) return;
        p.x = x; p.y = y; p.z = z;
    }

    private static ModelPart getMainPart(HumanoidModel<?> model, BoneTarget bone, boolean mirror) {
        return switch (bone) {
            case HEAD      -> model.head;
            case BODY      -> model.body;
            case RIGHT_ARM -> mirror ? model.leftArm  : model.rightArm;
            case LEFT_ARM  -> mirror ? model.rightArm : model.leftArm;
            case RIGHT_LEG -> mirror ? model.leftLeg  : model.rightLeg;
            case LEFT_LEG  -> mirror ? model.rightLeg : model.leftLeg;
            default        -> null;
        };
    }

    private static ModelPart getWearPart(PlayerModel<?> model, BoneTarget bone, boolean mirror) {
        return switch (bone) {
            case HEAD      -> model.hat;
            case BODY      -> model.jacket;
            case RIGHT_ARM -> mirror ? model.leftSleeve  : model.rightSleeve;
            case LEFT_ARM  -> mirror ? model.rightSleeve : model.leftSleeve;
            case RIGHT_LEG -> mirror ? model.leftPants   : model.rightPants;
            case LEFT_LEG  -> mirror ? model.rightPants  : model.leftPants;
            default        -> null;
        };
    }
}
