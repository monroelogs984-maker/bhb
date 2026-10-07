package com.bromax.bromaxbattle.animation;

import com.bromax.bromaxbattle.BromaxBattle;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

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

    /**
     * Holds {@code animation} at exactly {@code tick} for this entity until replaced or cleared.
     * Used by the preview tool to render poses at chosen points in time.
     */
    public void pose(UUID playerId, AnimationDefinition animation, float tick) {
        pose(playerId, animation, tick, null);
    }

    /** As {@link #pose(UUID, AnimationDefinition, float)}, rendering with {@code grip} instead of the animation's own. */
    public void pose(UUID playerId, AnimationDefinition animation, float tick, WeaponGrip grip) {
        if (playerId == null || animation == null) return;
        AnimationState state = new AnimationState(animation, 1.0f, null);
        state.frozen = true;
        state.gripOverride = grip;
        state.tickF = Math.max(0f, tick);
        state.refreshCache();
        state.refreshCache(); // second pass makes prev == current, so no interpolation
        states.put(playerId, state);
        cachedPartialTick.put(playerId, 1.0f);
        lastInterpNanos.put(playerId, System.nanoTime());
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
        // WEAPON_HAND keyframe values are scaled down as garnish on top of the grip
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

    /**
     * Grip rotation for the held item, or null when inactive. The weapon turns
     * from the vanilla hold into the animation's ending orientation over the
     * windup (smoothstep, full by hit-window start) and blends back out after
     * the strike. Returned quaternion is pre-interpolated — apply directly.
     */
    public org.joml.Quaternionf getGripRotation(UUID playerId, boolean mainhand) {
        AnimationState state = mainhand ? states.get(playerId) : offhandStates.get(playerId);
        if (state == null) return null;
        WeaponGrip grip = state.gripOverride != null ? state.gripOverride : state.animation.grip;
        if (grip.rotation == null) return null;

        // Interpolate the ramp between ticks so the turn runs at the same 40Hz
        // the body animation does instead of stepping at raw tick rate
        float pt = cachedPartialTick.getOrDefault(playerId, 1.0f);
        float tickInterp = Math.max(0f, state.tickF - (1f - pt) * state.speedMultiplier);

        float blend = state.getBlendFactor(tickInterp);
        float hitStart = Math.max(1f, state.animation.hitWindowStart);
        float ramp = Math.min(1f, tickInterp / hitStart);
        ramp = ramp * ramp * (3f - 2f * ramp); // smoothstep
        float factor = Math.min(ramp, blend) * state.animation.gripScale;
        if (factor <= 0.001f) return null;

        return new org.joml.Quaternionf().slerp(grip.rotation, factor);
    }

    /** Throttled interpolation partial tick for this player (same source the model uses). */
    public float getPartialTick(UUID playerId) {
        return cachedPartialTick.getOrDefault(playerId, 1.0f);
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
            if (state.frozen) return false;
            try {
                state.tickF += state.speedMultiplier;
                if (state.isComplete()) return true;
                if (state.tickF > state.animation.duration * 4f) return true;
                state.refreshCache();
                return false;
            } catch (Exception e) {
                BromaxBattle.LOGGER.warn("[BHB] Animation tick error for {}, clearing: {}",
                        entry.getKey(), e.getMessage());
                return true;
            }
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
        if (mainhand != null && mainhand.frozen) {
            cachedPartialTick.put(playerId, 1.0f);
            partialTick = 1.0f;
        }

        long now    = System.nanoTime();
        Long lastNs = lastInterpNanos.get(playerId);
        if (mainhand != null && mainhand.frozen) {
            // keep the frozen partial tick
        } else if (lastNs == null || (now - lastNs) >= INTERP_INTERVAL_NS) {
            lastInterpNanos.put(playerId, now);
            cachedPartialTick.put(playerId, partialTick);
        } else {
            partialTick = cachedPartialTick.getOrDefault(playerId, partialTick);
        }

        AnimationState idle = idleStates.get(playerId);

        // Vanilla torso pose, so the arms and head can be re-attached after BHB moves it
        ModelPart torso = model.body;
        float vbx = torso.xRot, vby = torso.yRot, vbz = torso.zRot;
        float vpx = torso.x, vpy = torso.y, vpz = torso.z;

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
            attachToTorso(model, vbx, vby, vbz, vpx, vpy, vpz);
            // A real off-hand attack animates the left arm itself
            AnimationState driver = mainhand != null ? mainhand : (offhand == null ? idle : null);
            if (driver != null && driver.cachedDeltas != null && driver.animation.blendMask.contains(BoneTarget.LEFT_ARM)) {
                poseOffArm(model, driver.animation.category, driver.getBlendFactor(driver.tickF));
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
        float blend = state.getBlendFactor(state.tickF);

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
                    // Add animation delta on top of vanilla walking so legs keep swinging normally
                    float s = mirror ? -1f : 1f;
                    addDelta(main, c[0], s * c[1], s * c[2]);
                    addDelta(wear, c[0], s * c[1], s * c[2]);
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
                    // Lerp body yaw back to current vanilla yaw during blendout
                    float vanillaRY = main != null ? main.yRot : 0f;
                    float bodyRY = Float.isNaN(state.lockedBodyRY)
                                 ? vanillaRY
                                 : state.lockedBodyRY * blend + vanillaRY * (1f - blend);
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
    // Torso attachment
    // -------------------------------------------------------------------------

    private static final float HIP_Y = 12f; // torso cube is 12px tall below its neck pivot
    private static final float TORSO_HIP_SCALE = 0.6f;

    /**
     * In HumanoidModel the head and arms are not children of the body: their pivots sit at
     * fixed points, so any torso lean or tilt the animation adds leaves the shoulders behind
     * (arms appear to sprout from the back or side of the chest). This takes the rotation BHB
     * added to the torso, pivots it at the hips so the figure bends at the waist with the legs
     * planted, and carries the head and both shoulders along with it. Arm angles are left as
     * authored: the clock/degree reference describes where the weapon points in the world, so
     * only the shoulder pivots move.
     */
    private static void attachToTorso(HumanoidModel<?> model, float vbx, float vby, float vbz,
                                      float vpx, float vpy, float vpz) {
        ModelPart torso = model.body;
        if (Math.abs(torso.xRot - vbx) < 1e-4f && Math.abs(torso.yRot - vby) < 1e-4f
                && Math.abs(torso.zRot - vbz) < 1e-4f) return;

        org.joml.Quaternionf vanilla = new org.joml.Quaternionf().rotationZYX(vbz, vby, vbx);
        org.joml.Quaternionf posed   = new org.joml.Quaternionf().rotationZYX(torso.zRot, torso.yRot, torso.xRot);
        org.joml.Quaternionf delta   = new org.joml.Quaternionf(posed).mul(new org.joml.Quaternionf(vanilla).conjugate());
        // Torso angles were authored around the neck pivot, where they only rock the chest;
        // bent at the hips the same angle tips the whole upper body, so scale it down
        delta = new org.joml.Quaternionf().slerp(delta, TORSO_HIP_SCALE);
        org.joml.Vector3f e = new org.joml.Quaternionf(delta).mul(vanilla).getEulerAnglesZYX(new org.joml.Vector3f());
        torso.xRot = e.x; torso.yRot = e.y; torso.zRot = e.z;

        // Hip point in model space, from the vanilla torso pose
        org.joml.Vector3f hip = new org.joml.Vector3f(0f, HIP_Y, 0f).rotate(vanilla).add(vpx, vpy, vpz);

        moveAboutHip(torso, delta, hip, vpx, vpy, vpz);
        moveAboutHip(model.head, delta, hip, model.head.x, model.head.y, model.head.z);
        moveAboutHip(model.rightArm, delta, hip, model.rightArm.x, model.rightArm.y, model.rightArm.z);
        moveAboutHip(model.leftArm, delta, hip, model.leftArm.x, model.leftArm.y, model.leftArm.z);

        if (model instanceof PlayerModel<?> pm) {
            pm.jacket.copyFrom(torso);
            pm.hat.copyFrom(model.head);
            pm.rightSleeve.copyFrom(model.rightArm);
            pm.leftSleeve.copyFrom(model.leftArm);
        }
    }

    private static void moveAboutHip(ModelPart part, org.joml.Quaternionf delta, org.joml.Vector3f hip,
                                     float x, float y, float z) {
        org.joml.Vector3f p = new org.joml.Vector3f(x, y, z).sub(hip).rotate(delta).add(hip);
        part.x = p.x; part.y = p.y; part.z = p.z;
    }


    // -------------------------------------------------------------------------
    // Off arm
    // -------------------------------------------------------------------------

    private static final float HAND_REACH = 10f;  // shoulder pivot to fist, model pixels
    private static final float COUNTER_SWING = 0.3f;
    private static final float COUNTER_MAX   = 0.5f;
    private static final float COUNTER_OUT   = -0.12f; // left arm held slightly away from the body

    /**
     * The mass-generated animations set the left arm to a fixed fraction of the right arm
     * (0.5 one-handed, 0.85 two-handed) with the same sign, so as the weapon arm swings across
     * the body the left arm swings outward into a T-pose, and two-handed grips never meet.
     * Replaces it: two-handed weapons reach the left hand to the right hand every frame, and
     * one-handed weapons swing the left arm back as a counterbalance to the strike. Paired
     * weapons (gauntlets, claws, sai, nunchaku) and bows keep their authored left arm.
     * {@code weight} fades the override in and out with the animation's own blend.
     */
    private static void poseOffArm(HumanoidModel<?> model, com.bromax.bromaxbattle.weapon.WeaponCategory cat, float weight) {
        if (cat == null || cat.isRanged() || weight <= 0.001f) return;
        switch (cat) {
            case GAUNTLETS, CLAW, SAI, NUNCHAKU -> { return; }
            default -> { }
        }
        ModelPart right = model.rightArm, left = model.leftArm;
        float tx, ty, tz;
        if (cat.isTwoHanded()) {
            org.joml.Vector3f hand = new org.joml.Quaternionf().rotationZYX(right.zRot, right.yRot, right.xRot)
                    .transform(new org.joml.Vector3f(-1f, HAND_REACH, 0f)).add(right.x, right.y, right.z);
            org.joml.Vector3f d = hand.sub(left.x, left.y, left.z);
            if (d.lengthSquared() < 1e-4f) return;
            d.normalize();
            // Arm hangs along +Y; with yRot 0, rotationZYX maps it to (-cos x sin z, cos x cos z, sin x)
            tx = (float) Math.asin(Math.max(-1f, Math.min(1f, d.z)));
            tz = (float) Math.atan2(-d.x, d.y);
            ty = 0f;
        } else {
            float strike = VANILLA_RIGHT_ARM_RX - right.xRot; // how far the weapon arm is raised forward
            tx = Math.max(-COUNTER_MAX, Math.min(COUNTER_MAX, strike * COUNTER_SWING));
            ty = 0f;
            tz = COUNTER_OUT;
        }
        left.xRot = Mth.lerp(weight, left.xRot, tx);
        left.yRot = Mth.lerp(weight, left.yRot, ty);
        left.zRot = Mth.lerp(weight, left.zRot, tz);
        if (model instanceof PlayerModel<?> pm) pm.leftSleeve.copyFrom(left);
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
