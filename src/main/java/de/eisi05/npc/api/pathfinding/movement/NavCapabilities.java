package de.eisi05.npc.api.pathfinding.movement;

import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a kind of entity <b>can physically do</b> while moving: how it moves ({@link MovementMode}), climb ladders or walls, swim, jump (and how high), jump
 * over gaps, open doors, how it looks while swimming, ...
 * <p>
 * Capabilities are looked up per {@link EntityType} with {@link #of(EntityType)} and are used in two places:
 * <ul>
 *     <li>the pathfinders ({@link AbstractPathfinder#useCapabilities(NavCapabilities)}) plan only moves the entity can perform, so a cow never plans a
 *     ladder, a dolphin never leaves the water and a bat flies straight through open space;</li>
 *     <li>{@code PathTask} uses them while moving (jump strength, swim pose, door opening, special moves).</li>
 * </ul>
 * Capabilities <b>restrict</b> the standard abilities of the {@link NavOptions} a pathfinder was configured with ({@code climb/swim/gap jump/doors}: AND,
 * limits: smaller value, {@code avoidWater}: OR) and <b>apply</b> entity-specific special abilities: the movement mode, wall climbing and the jump strength.
 * <p>
 * The built-in table is deliberately <b>permissive</b>: unknown entity types, players and humanoids can do everything; only behaviour that is unambiguous in
 * vanilla Minecraft is restricted. Everything is overridable with {@link #register(EntityType, NavCapabilities)}.
 * <p>
 * Instances are immutable; the withers ({@code canClimb(...)}, {@code movement(...)}, ...) return modified copies.
 */
public final class NavCapabilities
{
    /**
     * Walking entity, everything allowed, upright swimming (no swim pose), normal jump (about 1.25 blocks), default limits. Used for unknown entity types.
     */
    public static final NavCapabilities DEFAULT = new NavCapabilities(new Data());

    private static final Map<String, NavCapabilities> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Mutable holder used to build modified copies.
     */
    private static final class Data
    {
        MovementMode movement = MovementMode.GROUND;
        boolean canClimb = true;
        boolean canClimbWalls = false;
        boolean canCrouch = false;
        double crouchRatio = 1.5 / 1.8;
        boolean canSwim = true;
        boolean swimPose = false;
        boolean canGapJump = true;
        double maxJumpHeight = 1.25;
        double maxFallDistance = 3.0;
        boolean canOpenDoors = true;
        boolean avoidWater = false;
        double swimSpeedFactor = 0.55;
        double flySpeedFactor = 1.0;
    }

    private final MovementMode movement;
    private final boolean canClimb;
    private final boolean canClimbWalls;
    private final boolean canCrouch;
    private final double crouchRatio;
    private final boolean canSwim;
    private final boolean swimPose;
    private final boolean canGapJump;
    private final double maxJumpHeight;
    private final double maxFallDistance;
    private final boolean canOpenDoors;
    private final boolean avoidWater;
    private final double swimSpeedFactor;
    private final double flySpeedFactor;

    static
    {
        registerDefaults();
    }

    private NavCapabilities(@NotNull Data d)
    {
        this.movement = d.movement;
        this.canClimb = d.canClimb;
        this.canClimbWalls = d.canClimbWalls;
        this.canCrouch = d.canCrouch;
        this.crouchRatio = d.crouchRatio;
        this.canSwim = d.canSwim;
        this.swimPose = d.swimPose;
        this.canGapJump = d.canGapJump;
        this.maxJumpHeight = d.maxJumpHeight;
        this.maxFallDistance = d.maxFallDistance;
        this.canOpenDoors = d.canOpenDoors;
        this.avoidWater = d.avoidWater;
        this.swimSpeedFactor = d.swimSpeedFactor;
        this.flySpeedFactor = d.flySpeedFactor;
    }

    private @NotNull Data data()
    {
        Data d = new Data();
        d.movement = movement;
        d.canClimb = canClimb;
        d.canClimbWalls = canClimbWalls;
        d.canCrouch = canCrouch;
        d.crouchRatio = crouchRatio;
        d.canSwim = canSwim;
        d.swimPose = swimPose;
        d.canGapJump = canGapJump;
        d.maxJumpHeight = maxJumpHeight;
        d.maxFallDistance = maxFallDistance;
        d.canOpenDoors = canOpenDoors;
        d.avoidWater = avoidWater;
        d.swimSpeedFactor = swimSpeedFactor;
        d.flySpeedFactor = flySpeedFactor;
        return d;
    }

    /**
     * Returns the capabilities of an entity type ({@link #DEFAULT} if none are registered).
     *
     * @param type the entity type, may be null
     * @return the capabilities, never null
     */
    public static @NotNull NavCapabilities of(EntityType type)
    {
        return type == null ? DEFAULT : REGISTRY.getOrDefault(type.name(), DEFAULT);
    }

    /**
     * Registers (or replaces) the capabilities of an entity type.
     *
     * @param type         the entity type
     * @param capabilities the capabilities
     */
    public static void register(@NotNull EntityType type, @NotNull NavCapabilities capabilities)
    {
        REGISTRY.put(type.name(), capabilities);
    }

    /**
     * Registers the capabilities by entity type name. Useful for types that only exist on newer Minecraft versions (e.g. {@code "HAPPY_GHAST"}).
     *
     * @param typeName     the {@link EntityType#name()}
     * @param capabilities the capabilities
     */
    public static void register(@NotNull String typeName, @NotNull NavCapabilities capabilities)
    {
        REGISTRY.put(typeName.toUpperCase(), capabilities);
    }

    /**
     * Removes any registration for the entity type, so it uses {@link #DEFAULT} again.
     *
     * @param type the entity type
     */
    public static void unregister(@NotNull EntityType type)
    {
        REGISTRY.remove(type.name());
    }

    /**
     * Restores the built-in table.
     */
    public static void resetToDefaults()
    {
        REGISTRY.clear();
        registerDefaults();
    }

    private static void registerDefaults()
    {
        // humanoids that show the swimming animation / pose
        NavCapabilities humanoid = DEFAULT.swimPose(true);
        put(humanoid, "MANNEQUIN", "ZOMBIE", "DROWNED", "HUSK", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN", "SKELETON", "STRAY", "BOGGED",
                "WITHER_SKELETON", "PIGLIN", "PIGLIN_BRUTE", "PILLAGER", "VINDICATOR", "EVOKER", "ILLUSIONER");

        // only players can crouch (sneak) to fit through gaps that are lower than they are tall
        put(humanoid.canCrouch(true), "PLAYER");

        // livestock & big animals: no ladders, no doors
        NavCapabilities livestock = DEFAULT.canClimb(false).canOpenDoors(false);
        put(livestock, "COW", "MOOSHROOM", "PIG", "SHEEP", "LLAMA", "TRADER_LLAMA", "CAMEL", "CAMEL_HUSK", "SNIFFER", "ARMADILLO", "POLAR_BEAR", "PANDA",
                "RAVAGER", "HOGLIN", "ZOGLIN", "STRIDER", "WOLF", "FOX", "CAT", "OCELOT", "RABBIT", "CHICKEN", "FROG", "SLIME", "MAGMA_CUBE");

        // strong jumpers: horses (jump strength 0.4 - 1.0 in vanilla, about 2 blocks on average) and goats
        NavCapabilities jumper = livestock.maxJumpHeight(2.0);
        put(jumper, "HORSE", "DONKEY", "MULE", "SKELETON_HORSE", "ZOMBIE_HORSE", "GOAT");

        // golems are too heavy to swim (they sink and walk on the bottom) and cannot open doors
        put(DEFAULT.canClimb(false).canSwim(false).canOpenDoors(false), "IRON_GOLEM", "COPPER_GOLEM");

        // creatures that take damage from water
        put(DEFAULT.canClimb(false).canSwim(false).avoidWater(true).canOpenDoors(false), "SNOW_GOLEM");
        put(DEFAULT.avoidWater(true).canOpenDoors(false), "ENDERMAN");

        // handless crawlers; spiders additionally climb any wall
        put(DEFAULT.canOpenDoors(false), "CREEPER", "SILVERFISH", "ENDERMITE", "WARDEN");
        put(DEFAULT.canOpenDoors(false).canClimbWalls(true), "SPIDER", "CAVE_SPIDER");

        // fully aquatic: stay in water, swim at full speed, no ladders, no jumps, no doors
        NavCapabilities aquatic = DEFAULT.movement(MovementMode.AQUATIC).canClimb(false).canGapJump(false).canOpenDoors(false).maxJumpHeight(0.55)
                .swimSpeedFactor(1.0);
        put(aquatic, "COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "SQUID", "GLOW_SQUID", "DOLPHIN", "GUARDIAN", "ELDER_GUARDIAN", "TADPOLE");

        // semi aquatic: walk, but swim well
        NavCapabilities semiAquatic = DEFAULT.canClimb(false).canGapJump(false).canOpenDoors(false).maxJumpHeight(0.55);
        put(semiAquatic.swimSpeedFactor(0.8), "AXOLOTL");
        put(semiAquatic.swimSpeedFactor(0.6), "TURTLE");

        // fliers: free 3D movement, no ground, no ladders, no gap jumps, no doors
        NavCapabilities flyer = DEFAULT.movement(MovementMode.FLYING).canClimb(false).canSwim(false).canGapJump(false).canOpenDoors(false).maxJumpHeight(0.55);
        put(flyer, "BAT", "BEE", "PARROT", "ALLAY", "VEX", "GHAST", "HAPPY_GHAST", "PHANTOM", "BLAZE", "ENDER_DRAGON", "WITHER");
    }

    private static void put(@NotNull NavCapabilities capabilities, String @NotNull ... names)
    {
        for(String name : names)
            REGISTRY.put(name, capabilities);
    }

    /**
     * Returns a copy of the options adjusted to what this entity can do.
     * <ul>
     *     <li>Standard abilities are restricted: {@code canClimb/canSwim/canGapJump/canOpenDoors} are AND-ed, {@code avoidWater} is OR-ed, fall distance
     *     uses the smaller value.</li>
     *     <li>Jump height: the entity's jump strength decides how high it can jump and shapes gap-jump arcs; an explicit
     *     {@link NavOptions#maxJumpHeight(double)} can only lower the ledge height further.</li>
     *     <li>Special abilities are applied: movement mode and wall climbing.</li>
     * </ul>
     *
     * @param options the configured options
     * @return the adjusted copy
     */
    public @NotNull NavOptions restrict(@NotNull NavOptions options)
    {
        NavOptions copy = options.copy();
        copy.canClimb &= canClimb;
        copy.canSwim &= canSwim;
        copy.canGapJump &= canGapJump;
        copy.canOpenDoors &= canOpenDoors;
        copy.avoidWater |= avoidWater;
        copy.maxFallDistance = Math.min(copy.maxFallDistance, maxFallDistance);

        copy.arcHeight = maxJumpHeight;
        copy.maxJumpHeight = copy.jumpHeightExplicit ? Math.min(copy.maxJumpHeight, maxJumpHeight) : maxJumpHeight;

        copy.canClimbWalls |= canClimbWalls;
        copy.crouchRatio = canCrouch && copy.allowCrouch ? crouchRatio : 0.0;
        if(movement != MovementMode.GROUND)
            copy.mode = movement;
        return copy;
    }

    /**
     * @return how the entity moves (walking, swimming only, flying)
     */
    public @NotNull MovementMode movement()
    {
        return movement;
    }

    /**
     * @return whether the entity flies (shortcut for {@code movement() == FLYING})
     */
    public boolean canFly()
    {
        return movement == MovementMode.FLYING;
    }

    /**
     * @return whether the entity can climb ladders, vines, scaffolding and similar
     */
    public boolean canClimb()
    {
        return canClimb;
    }

    /**
     * @return whether the entity can crouch (sneak) to pass gaps that are too low to walk through standing; only players
     */
    public boolean canCrouch()
    {
        return canCrouch;
    }

    /**
     * @return the height of the crouched bounding box relative to the standing one (1.5 / 1.8 for players). It is a ratio, so it automatically follows the
     * NPC's scale
     */
    public double crouchRatio()
    {
        return crouchRatio;
    }

    /**
     * @return whether the entity can climb any solid wall like a spider
     */
    public boolean canClimbWalls()
    {
        return canClimbWalls;
    }

    /**
     * @return whether the entity may swim through deep water
     */
    public boolean canSwim()
    {
        return canSwim;
    }

    /**
     * @return whether the entity is shown in the swimming pose while swimming (players and humanoids); other entities just move through the water upright
     */
    public boolean swimPose()
    {
        return swimPose;
    }

    /**
     * @return whether the entity can jump over gaps
     */
    public boolean canGapJump()
    {
        return canGapJump;
    }

    /**
     * @return how high the entity can jump (the apex of its jump in blocks); values up to the step height (0.55) mean "cannot jump"
     */
    public double maxJumpHeight()
    {
        return maxJumpHeight;
    }

    /**
     * @return the jump arc of this entity, derived from {@link #maxJumpHeight()}; used for the take-off velocity and gap-jump trajectories
     */
    public @NotNull JumpArc jumpArc()
    {
        return JumpArc.forHeight(Math.max(maxJumpHeight, 0.6));
    }

    /**
     * @return the largest drop the entity may take
     */
    public double maxFallDistance()
    {
        return maxFallDistance;
    }

    /**
     * @return whether the entity can open doors, fence gates and trapdoors
     */
    public boolean canOpenDoors()
    {
        return canOpenDoors;
    }

    /**
     * @return whether the entity avoids water (no swimming, heavy penalty for wading)
     */
    public boolean avoidWater()
    {
        return avoidWater;
    }

    /**
     * @return swimming speed relative to the walking speed
     */
    public double swimSpeedFactor()
    {
        return swimSpeedFactor;
    }

    /**
     * @return flying speed relative to the walking speed
     */
    public double flySpeedFactor()
    {
        return flySpeedFactor;
    }

    /**
     * @param movement how the entity moves
     * @return a modified copy
     */
    public @NotNull NavCapabilities movement(@NotNull MovementMode movement)
    {
        Data d = data();
        d.movement = movement;
        return new NavCapabilities(d);
    }

    /**
     * @param canClimb whether the entity can climb ladders, vines and scaffolding
     * @return a modified copy
     */
    public @NotNull NavCapabilities canClimb(boolean canClimb)
    {
        Data d = data();
        d.canClimb = canClimb;
        return new NavCapabilities(d);
    }

    /**
     * @param canClimbWalls whether the entity can climb any solid wall like a spider
     * @return a modified copy
     */
    public @NotNull NavCapabilities canClimbWalls(boolean canClimbWalls)
    {
        Data d = data();
        d.canClimbWalls = canClimbWalls;
        return new NavCapabilities(d);
    }

    /**
     * @param canCrouch whether the entity can crouch to fit through low gaps
     * @return a modified copy
     */
    public @NotNull NavCapabilities canCrouch(boolean canCrouch)
    {
        Data d = data();
        d.canCrouch = canCrouch;
        return new NavCapabilities(d);
    }

    /**
     * @param crouchRatio the crouched height relative to the standing height (players: 1.5 / 1.8)
     * @return a modified copy
     */
    public @NotNull NavCapabilities crouchRatio(double crouchRatio)
    {
        Data d = data();
        d.crouchRatio = Math.clamp(crouchRatio, 0.3, 1.0);
        return new NavCapabilities(d);
    }

    /**
     * @param canSwim whether the entity may swim through deep water
     * @return a modified copy
     */
    public @NotNull NavCapabilities canSwim(boolean canSwim)
    {
        Data d = data();
        d.canSwim = canSwim;
        return new NavCapabilities(d);
    }

    /**
     * @param swimPose whether the swimming pose is shown while swimming
     * @return a modified copy
     */
    public @NotNull NavCapabilities swimPose(boolean swimPose)
    {
        Data d = data();
        d.swimPose = swimPose;
        return new NavCapabilities(d);
    }

    /**
     * @param canGapJump whether the entity can jump over gaps
     * @return a modified copy
     */
    public @NotNull NavCapabilities canGapJump(boolean canGapJump)
    {
        Data d = data();
        d.canGapJump = canGapJump;
        return new NavCapabilities(d);
    }

    /**
     * Sets the jump strength as the height of the jump's apex. {@code 1.25} is a normal jump, {@code 2.0} a horse, {@code 0.55} or less means the entity
     * cannot jump at all.
     *
     * @param maxJumpHeight the apex height in blocks
     * @return a modified copy
     */
    public @NotNull NavCapabilities maxJumpHeight(double maxJumpHeight)
    {
        Data d = data();
        d.maxJumpHeight = Math.clamp(maxJumpHeight, 0.0, 6.0);
        return new NavCapabilities(d);
    }

    /**
     * @param maxFallDistance the largest drop the entity may take
     * @return a modified copy
     */
    public @NotNull NavCapabilities maxFallDistance(double maxFallDistance)
    {
        Data d = data();
        d.maxFallDistance = Math.max(0.0, maxFallDistance);
        return new NavCapabilities(d);
    }

    /**
     * @param canOpenDoors whether the entity can open doors, fence gates and trapdoors (otherwise closed ones block the path)
     * @return a modified copy
     */
    public @NotNull NavCapabilities canOpenDoors(boolean canOpenDoors)
    {
        Data d = data();
        d.canOpenDoors = canOpenDoors;
        return new NavCapabilities(d);
    }

    /**
     * @param avoidWater whether the entity avoids water
     * @return a modified copy
     */
    public @NotNull NavCapabilities avoidWater(boolean avoidWater)
    {
        Data d = data();
        d.avoidWater = avoidWater;
        return new NavCapabilities(d);
    }

    /**
     * @param swimSpeedFactor swimming speed relative to the walking speed (default 0.55)
     * @return a modified copy
     */
    public @NotNull NavCapabilities swimSpeedFactor(double swimSpeedFactor)
    {
        Data d = data();
        d.swimSpeedFactor = Math.clamp(swimSpeedFactor, 0.05, 2.0);
        return new NavCapabilities(d);
    }

    /**
     * @param flySpeedFactor flying speed relative to the walking speed (default 1.0)
     * @return a modified copy
     */
    public @NotNull NavCapabilities flySpeedFactor(double flySpeedFactor)
    {
        Data d = data();
        d.flySpeedFactor = Math.clamp(flySpeedFactor, 0.05, 3.0);
        return new NavCapabilities(d);
    }

    @Override
    public String toString()
    {
        return "NavCapabilities[" + movement + ", climb=" + canClimb + ", wallClimb=" + canClimbWalls + ", crouch=" + canCrouch + ", swim=" + canSwim + ", swimPose=" + swimPose +
                ", gapJump=" + canGapJump + ", jump=" + maxJumpHeight + ", fall=" + maxFallDistance + ", doors=" + canOpenDoors + ", avoidWater=" +
                avoidWater + ", swimSpeed=" + swimSpeedFactor + ", flySpeed=" + flySpeedFactor + "]";
    }
}
