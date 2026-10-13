package de.eisi05.npc.api.ai.goals;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.environment.WorldAccess;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.utils.*;
import de.eisi05.npc.api.utils.serialize.NpcRegistry;
import de.eisi05.npc.api.wrapper.packets.AnimatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.*;

/**
 * A goal that makes the NPC attack nearby entities that match a predicate. The attack behavior varies based on the item in the NPC's main hand:
 * <ul>
 *     <li><b>WeaponMechanics guns:</b> Custom ranged attack behavior</li>
 *     <li><b>Bow/Trident/Crossbow:</b> Long range attacks</li>
 *     <li><b>Sword/Axe:</b> Short range melee attacks</li>
 *     <li><b>Other:</b> Default melee attacks</li>
 * </ul>
 * Only activates when a valid target is in range and line of sight. Once a target is locked, the NPC continues attacking until the target becomes
 * unreachable or invalid.
 */
@SuppressWarnings({"ScheduledForRemoval", "removal"})
public class AttackEntityGoal extends EntityTargetGoal
{
    @Serial
    private static final long serialVersionUID = 1L;

    private static final double WEAPONMECHANICS_ATTACK_RANGE = 25.0;
    private static final double BOW_ATTACK_RANGE = 15.0;
    private static final double MELEE_ATTACK_RANGE = 3.0;
    private static final double LINE_OF_SIGHT_RANGE = 25.0;
    private static final int BOW_DRAW_DELAY_TICKS = 25;
    private static final double KITING_DISTANCE = 3.0;
    private static final double OPTIMAL_RANGED_DISTANCE = 6.0;
    private static final int SEARCH_INTERVAL = 20;
    private static final long LOS_CACHE_MS = 250;
    private static final long RETRY_MOVEMENT_MS = 1500;

    private static final double WEAPONMECHANICS_ATTACK_RANGE_SQ = WEAPONMECHANICS_ATTACK_RANGE * WEAPONMECHANICS_ATTACK_RANGE;
    private static final double BOW_ATTACK_RANGE_SQ = BOW_ATTACK_RANGE * BOW_ATTACK_RANGE;
    private static final double MELEE_ATTACK_RANGE_SQ = MELEE_ATTACK_RANGE * MELEE_ATTACK_RANGE;
    private static final double LINE_OF_SIGHT_RANGE_SQ = LINE_OF_SIGHT_RANGE * LINE_OF_SIGHT_RANGE;
    private static final double KITING_DISTANCE_SQ = KITING_DISTANCE * KITING_DISTANCE;
    private static final double OPTIMAL_RANGED_DISTANCE_SQ = OPTIMAL_RANGED_DISTANCE * OPTIMAL_RANGED_DISTANCE;

    @JsonAdapter(TargetFilterAdapter.class)
    private SerializableBiPredicate<LivingEntity, NPC> targetFilter;
    private double customAttackRange;
    private double speed;

    private transient int attackCooldown;
    private transient boolean isAttacking;
    private transient WalkToLocationGoal movementGoal;
    private transient boolean isUsing;
    private transient List<WeakReference<Player>> cachedViewers;
    private transient LookController look;
    private transient LivingEntity losTarget;
    private transient boolean losResult;
    private transient long losCheckedAt;
    private transient long retryMovementAt;
    private transient int pathRecalculationCooldown;
    private transient boolean isKiting;

    private transient long nextSearchAt;

    /**
     * Creates an AttackEntityGoal with a filter for valid targets.
     *
     * @param targetFilter A predicate to filter valid targets
     */
    public AttackEntityGoal(@NotNull RegistryPredicate<LivingEntity> targetFilter)
    {
        this(targetFilter, -1);
    }

    /**
     * Creates an AttackEntityGoal with a filter for valid targets and custom attack range.
     *
     * @param targetFilter      A predicate to filter valid targets
     * @param customAttackRange Custom attack range (-1 for default based on weapon)
     */
    public AttackEntityGoal(@NotNull RegistryPredicate<LivingEntity> targetFilter, double customAttackRange)
    {
        this(targetFilter, customAttackRange, WalkToLocationGoal.DEFAULT_SPEED);
    }

    /**
     * Creates an AttackEntityGoal with a filter for valid targets, custom attack range, and custom movement speed.
     *
     * @param targetFilter      A predicate to filter valid targets
     * @param customAttackRange Custom attack range (-1 for default based on weapon)
     * @param speed             Custom movement speed (clamped between 0.1 and 1.0)
     */
    public AttackEntityGoal(@NotNull RegistryPredicate<LivingEntity> targetFilter, double customAttackRange, double speed)
    {
        super(Priority.ALWAYS);
        this.targetFilter = targetFilter;
        this.customAttackRange = customAttackRange;
        this.speed = Math.clamp(speed, 0.1, 1.0);
    }

    /**
     * Copy constructor for duplicating an existing AttackEntityGoal.
     *
     * @param goal the goal to copy
     */
    private AttackEntityGoal(@NotNull AttackEntityGoal goal)
    {
        super(goal);
        this.targetFilter = goal.targetFilter;
        this.customAttackRange = goal.customAttackRange;
        this.speed = goal.speed;
    }

    /**
     * Gets the target filter for this goal.
     *
     * @return the target filter as a {@link RegistryPredicate}, or {@code null} if incompatible
     */
    public @Nullable RegistryPredicate<LivingEntity> getTargetFilter()
    {
        return targetFilter instanceof RegistryPredicate<LivingEntity> registryPredicate ? registryPredicate : null;
    }

    /**
     * Sets the target filter for this goal.
     *
     * @param targetFilter the new target filter predicate
     */
    public void setTargetFilter(@Nullable RegistryPredicate<LivingEntity> targetFilter)
    {
        this.targetFilter = targetFilter;
    }

    /**
     * Gets the custom attack range for this goal.
     *
     * @return the custom attack range
     */
    public double getCustomAttackRange()
    {
        return customAttackRange;
    }

    /**
     * Sets the custom attack range for this goal.
     *
     * @param customAttackRange the new custom attack range
     */
    public void setCustomAttackRange(double customAttackRange)
    {
        this.customAttackRange = customAttackRange;
    }

    /**
     * Gets the speed for this goal.
     *
     * @return the speed
     */
    public double getSpeed()
    {
        return speed;
    }

    /**
     * Sets the speed for this goal.
     *
     * @param speed the new speed
     */
    public void setSpeed(double speed)
    {
        this.speed = speed;
    }

    /**
     * Checks if this goal can be used by the NPC.
     *
     * @param npc the NPC to check
     * @return true if a valid target is found or an existing target is still valid
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        if(targetFilter == null)
            return false;

        if(!super.canUse(npc))
            return false;

        refreshTarget(npc);
        if(target != null && targetValid() && canContinue(npc))
            return true;

        LivingEntity potentialTarget = findTarget(npc);
        if(potentialTarget == null)
            return false;

        this.target = potentialTarget;
        refreshTarget(npc);
        return true;
    }

    /**
     * Starts the attack goal, initializing all necessary state.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        this.attackCooldown = 0;
        this.isAttacking = true;
        movementGoal = null;
        isUsing = false;
        losCheckedAt = 0;
        retryMovementAt = 0;
        pathRecalculationCooldown = 0;
        look().reset();
        isKiting = false;
        setUsingItemState(npc, false);
        updateCachedViewers(npc);
    }

    /**
     * Ticks the attack goal, handling movement, targeting, and attacks.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        refreshTarget(npc);
        if(awaitingSnapshot())
            return;

        if(target == null || !targetValid())
        {
            isAttacking = false;
            stopMovement(npc);
            return;
        }

        Location npcLoc = npc.getLocation();

        lookAtTarget(npc, look());

        Location targetLocation = getTargetLocation();
        double distance = npcLoc.distanceSquared(targetLocation);
        double attackRange = getAttackRange(npc);

        if(distance > attackRange)
        {
            if(distance > LINE_OF_SIGHT_RANGE_SQ || !hasLineOfSightCached(npc, target))
            {
                stop(npc);
                return;
            }

            if(movementGoal != null && !movementGoal.isActive())
            {
                if(movementGoal.isUnreachable())
                    retryMovementAt = System.currentTimeMillis() + RETRY_MOVEMENT_MS;
                movementGoal = null;
            }

            Location ground = getTargetGround(npc);
            if(movementGoal == null)
            {
                if(ground != null && System.currentTimeMillis() >= retryMovementAt)
                    startMovement(npc, ground);
            }
            else
            {
                isKiting = false;

                if(ground != null)
                {
                    double tolerance = Math.clamp(Math.sqrt(distance) * 0.2, 1.0, 5.0);
                    if(movementGoal.horizontalTargetDistanceSq(ground) > tolerance * tolerance || movementGoal.verticalTargetDistance(ground) > 1.5)
                        movementGoal.updateTargetLocation(ground.clone(), npc);
                }

                movementGoal.tick(npc);

                distance = npc.getLocation().distanceSquared(targetLocation);
                if(distance <= attackRange)
                    stopMovement(npc);
                else
                    return;
            }
        }
        else if(isUsingRangedWeapon(npc))
        {
            if(distance < KITING_DISTANCE_SQ && !isKiting)
                startKiting(npc);
            else if(isKiting && distance >= OPTIMAL_RANGED_DISTANCE_SQ)
                stopKiting(npc);
            else if(isKiting)
            {
                if(movementGoal != null && movementGoal.isActive())
                {
                    movementGoal.tick(npc);
                    if(--pathRecalculationCooldown <= 0)
                        startKiting(npc);
                }
                else
                    isKiting = false;
                return;
            }
            else
                stopMovement(npc);
        }
        else
            stopMovement(npc);

        if(attackCooldown > 0)
        {
            attackCooldown--;
            return;
        }

        if(distance <= attackRange)
        {
            performAttack(npc);
            attackCooldown = getAttackCooldown(npc);
        }
    }

    /**
     * Stops the attack goal and cleans up state.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        stopMovement(npc);
        clearTarget();
        isAttacking = false;
        attackCooldown = 0;
        isUsing = false;
        setUsingItemState(npc, false);
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if the target is still valid and in range
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        if(!isAttacking || target == null)
            return false;

        refreshTarget(npc);
        if(awaitingSnapshot())
            return super.canContinue(npc);

        if(!targetValid())
            return false;

        if(!super.canContinue(npc))
            return false;

        Location npcLoc = npc.getLocation();
        Location targetLoc = getTargetLocation();

        if(!npcLoc.getWorld().equals(targetLoc.getWorld()))
            return false;

        if(!hasLineOfSightCached(npc, target))
            return false;

        double distance = npcLoc.distanceSquared(targetLoc);
        double attackRange = getAttackRange(npc);

        return distance <= Math.max(attackRange, LINE_OF_SIGHT_RANGE_SQ);
    }

    @Override
    protected boolean canBeRemovedNow(@NotNull NPC npc)
    {
        if(super.canBeRemovedNow(npc))
            return true;

        if(movementGoal != null && movementGoal.canContinue(npc))
        {
            if(isAirBelow(npc))
                return false;
        }
        return true;
    }

    @Override
    public @NotNull Goal copy()
    {
        return new AttackEntityGoal(this);
    }

    /**
     * Finds a valid target entity near the NPC.
     */
    private LivingEntity findTarget(@NotNull NPC npc)
    {
        long now = System.currentTimeMillis();
        if(now < nextSearchAt)
            return null;

        nextSearchAt = now + SEARCH_INTERVAL * 50L;
        Location loc = npc.getLocation();

        double wantedRange = Math.max(getAttackRange(npc), LINE_OF_SIGHT_RANGE);
        double searchRange = ownedRange(loc, wantedRange);
        if(searchRange <= 0)
            return null;

        Collection<Entity> nearby = loc.getWorld().getNearbyEntities(
                loc,
                searchRange,
                searchRange,
                searchRange,
                entity -> entity instanceof LivingEntity
        );

        List<LivingEntity> candidates = new ArrayList<>();
        for(Entity entity : nearby)
        {
            if(targetFilter.test((LivingEntity) entity, npc))
                candidates.add((LivingEntity) entity);
        }

        if(candidates.isEmpty())
            return null;

        candidates.sort(Comparator.comparingDouble(candidate -> candidate.getLocation().distanceSquared(loc)));
        for(LivingEntity candidate : candidates)
        {
            if(hasLineOfSight(npc, candidate))
                return candidate;
        }

        return null;
    }

    private @NotNull LookController look()
    {
        if(look == null)
            look = new LookController();
        return look;
    }

    /**
     * Line of sight with a short cache. {@code canUse}, {@code canContinue} and {@code tick} are all called every tick; without the cache this was up to two
     * ray traces per tick while in melee range.
     */
    private boolean hasLineOfSightCached(@NotNull NPC npc, @NotNull LivingEntity entity)
    {
        long now = System.currentTimeMillis();
        if(entity != losTarget || now - losCheckedAt >= LOS_CACHE_MS)
        {
            losResult = hasLineOfSight(npc, entity);
            losTarget = entity;
            losCheckedAt = now;
        }
        return losResult;
    }

    /**
     * Checks if the NPC has line of sight to the target entity.
     */
    private boolean hasLineOfSight(@NotNull NPC npc, @NotNull LivingEntity target)
    {
        Location npcLoc = npc.getLocation().clone().add(0, 1.6, 0);

        boolean current = target == this.target;
        Location base = current ? targetPosition() : target.getLocation();
        if(base == null)
            return false;
        Location targetLoc = base.clone().add(0, current ? targetEyeHeight() : target.getEyeHeight(), 0);

        World world = npcLoc.getWorld();
        if(world == null || targetLoc.getWorld() != world)
            return false;

        if(!Bukkit.isOwnedByCurrentRegion(world, Math.min(npcLoc.getBlockX(), targetLoc.getBlockX()) >> 4, Math.min(npcLoc.getBlockZ(), targetLoc.getBlockZ()) >> 4,
                Math.max(npcLoc.getBlockX(), targetLoc.getBlockX()) >> 4, Math.max(npcLoc.getBlockZ(), targetLoc.getBlockZ()) >> 4))
            return true;

        Vector direction = targetLoc.toVector().subtract(npcLoc.toVector());
        double distance = direction.length();

        if(distance == 0)
            return true;

        RayTraceResult result = world.rayTraceBlocks(
                npcLoc,
                direction.normalize(),
                distance,
                FluidCollisionMode.NEVER,
                true
        );

        return result == null || result.getHitBlock() == null;
    }

    /**
     * Gets the attack range based on the NPC's held item.
     */
    private double getAttackRange(@NotNull NPC npc)
    {
        if(customAttackRange > 0)
            return customAttackRange * customAttackRange;

        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;

        if(mainHand == null)
            return MELEE_ATTACK_RANGE_SQ;

        if(getWeaponTitle(mainHand) != null)
            return WEAPONMECHANICS_ATTACK_RANGE_SQ;

        if(isRangedWeapon(mainHand.getType()))
            return BOW_ATTACK_RANGE_SQ;
        else
            return MELEE_ATTACK_RANGE_SQ;
    }

    /**
     * Gets the attack cooldown based on the NPC's held item.
     */
    private int getAttackCooldown(@NotNull NPC npc)
    {
        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;

        if(mainHand == null || !mainHand.hasItemMeta())
            return 5;

        String weaponTitle = getWeaponTitle(mainHand);
        if(weaponTitle != null)
        {
            try
            {
                Object config = Reflections.invokeMethod("me.deecaad.weaponmechanics.WeaponMechanics", "getInstance")
                        .thenInvoke("getWeaponConfigurations").get();
                if(config != null)
                {

                    int shotsPerSecond = (int) Reflections.invokeMethod(config, "getInt", weaponTitle + ".Shoot.Fully_Automatic_Shots_Per_Second", 0).get();
                    if(shotsPerSecond > 0)
                        return Math.max(1, 20 / shotsPerSecond);


                    int burstDelay = (int) Reflections.invokeMethod(config, "getInt", weaponTitle + ".Shoot.Burst.Ticks_Between_Each_Shot", 0).get();
                    if(burstDelay > 0)
                        return burstDelay;


                    int shootDelay = (int) Reflections.invokeMethod(config, "getInt", weaponTitle + ".Shoot.Delay_Between_Shots", 0).get();
                    if(shootDelay > 0)
                        return shootDelay;
                }
            }
            catch(NoClassDefFoundError | Exception ignored) {}

            return 10;
        }

        ItemMeta meta = mainHand.getItemMeta();
        if(meta == null)
            return 5;

        Collection<AttributeModifier> modifiers;
        try
        {
            //noinspection UnstableApiUsage
            modifiers = meta.getAttributeModifiers(Attribute.valueOf("GENERIC_ATTACK_SPEED"));
        }
        catch(Throwable e)
        {
            modifiers = meta.getAttributeModifiers(Attribute.ATTACK_SPEED);
        }

        if(modifiers == null)
            return 5;

        double speed = 0;
        for(AttributeModifier modifier : modifiers)
            speed += modifier.getAmount();

        return (int) (20 / speed);
    }

    /**
     * Performs an attack on the target.
     */
    private void performAttack(@NotNull NPC npc)
    {
        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;

        String weaponTitle = getWeaponTitle(mainHand);
        if(weaponTitle != null)
            performWeaponMechanicsAttack(npc, weaponTitle, mainHand);
        else if(mainHand != null && isRangedWeapon(mainHand.getType()))
            performRangedAttack(npc);
        else
            performMeleeAttack(npc);
    }

    /**
     * Utilizes WeaponMechanics API to gather weapon identification title safely.
     */
    private String getWeaponTitle(ItemStack item)
    {
        if(item == null || item.getType() == Material.AIR)
            return null;

        try
        {
            return (String) Reflections.invokeMethod("me.deecaad.weaponmechanics.WeaponMechanicsAPI", "getWeaponTitle", item).get();
        }
        catch(NoClassDefFoundError | Exception e)
        {
            return null;
        }
    }

    /**
     * Executes native programmatic WeaponMechanics shooting actions centered around the NPC.
     */
    private void performWeaponMechanicsAttack(@NotNull NPC npc, String weaponTitle, ItemStack weaponStack)
    {
        if(target == null || !targetValid())
            return;

        Player npcPlayer = ((ServerPlayer) npc.getServerPlayer()).getBukkitEntity();
        if(npcPlayer == null)
            return;

        Location targetPos = targetPosition();
        LivingEntity victim = target;
        if(victim == null || targetPos == null)
            return;

        try
        {
            Vector direction = targetPos.subtract(npc.getLocation()).toVector().normalize();
            Location location = npc.getLocation().clone().add(0, npc.getEyeHeight(null), 0);
            location.setDirection(direction);

            Object entityWrapper = Reflections.invokeMethod("me.deecaad.weaponmechanics.WeaponMechanics", "getInstance")
                    .thenInvoke("getEntityWrapper", npcPlayer).get();
            Reflections.invokeMethod("me.deecaad.weaponmechanics.WeaponMechanics", "getInstance")
                    .thenInvoke("getWeaponHandler").thenInvoke("getShootHandler")
                    .thenInvoke("shoot", entityWrapper, weaponTitle, weaponStack, location, true, true, false);
        }
        catch(NoClassDefFoundError | Exception e)
        {
            double fallbackDamage = getAttackDamage(npc);
            runOnTarget(victim, () -> victim.damage(fallbackDamage, npcPlayer));
        }
    }

    /**
     * Performs a ranged attack.
     */
    private void performRangedAttack(@NotNull NPC npc)
    {
        if(target == null || !targetValid() || isUsing)
            return;

        Entity bukkitEntity = ((net.minecraft.world.entity.Entity) npc.getEntity()).getBukkitEntity();
        double eyeHeight = (bukkitEntity instanceof LivingEntity le ? le.getEyeHeight() : bukkitEntity.getHeight()) -
                (npc.getOption(NpcOption.POSE) == org.bukkit.entity.Pose.SITTING ? 0.625 : 0);

        Location npcLoc = npc.getLocation().clone().add(0, eyeHeight * npc.getOption(NpcOption.SCALE), 0);

        final float speed = 2.5f;

        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;

        if(mainHand != null && (mainHand.getType() == Material.BOW || mainHand.getType() == Material.CROSSBOW))
        {
            setUsingItemState(npc, true);
            isUsing = true;

            // scheduled at the NPC's position; the shot itself runs in the NPC's region under the goal's lock, the arrow is spawned where it was drawn
            SchedulerProvider.get().runLaterAtLocation(npcLoc, () -> npc.getGoalExecutor().execute(() ->
            {
                setUsingItemState(npc, false);

                isUsing = false;

                if(target == null)
                    return;

                Location targetPos = targetPosition();
                if(targetPos == null)
                    return;

                Location targetLoc = targetPos.add(0, targetEyeHeight(), 0);
                Vector direction = targetLoc.toVector().subtract(npcLoc.toVector()).normalize();

                Runnable spawn = () ->
                {
                    AbstractArrow arrow;
                    if(mainHand == null || mainHand.getType() == Material.TRIDENT)
                        arrow = npcLoc.getWorld().spawnArrow(npcLoc, direction, speed, 0, Trident.class);
                    else
                        arrow = npcLoc.getWorld().spawnArrow(npcLoc, direction, speed, 0, Arrow.class);
                    if(npc.getServerPlayer() != null)
                        arrow.setShooter(((ServerPlayer) npc.getServerPlayer()).getBukkitEntity());
                    arrow.setDamage(getAttackDamage(npc));
                    arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    arrow.setShotFromCrossbow(mainHand.getType() == Material.CROSSBOW);

                    ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(arrow.getEntityId());
                    for(Player player : Bukkit.getOnlinePlayers())
                    {
                        if(player.getWorld().equals(npcLoc.getWorld()) && !npc.getViewers().contains(player.getUniqueId()))
                            ((CraftPlayer) player).getHandle().connection.send(removePacket);
                    }
                };

                if(Bukkit.isOwnedByCurrentRegion(npcLoc))
                    spawn.run();
                else
                    SchedulerProvider.get().runSyncAtLocation(npcLoc, spawn);
            }), BOW_DRAW_DELAY_TICKS);
        }
    }

    /**
     * Sets the NPC's item usage state (e.g., drawing a bow).
     *
     * @param npc   the NPC to update
     * @param using true if the NPC is using an item, false otherwise
     */
    private void setUsingItemState(@NotNull NPC npc, boolean using)
    {
        ServerPlayer serverPlayer = (ServerPlayer) npc.getServerPlayer();
        SynchedEntityData data = serverPlayer.getEntityData();
        EntityDataAccessor<Byte> accessor = EntityDataSerializers.BYTE.createAccessor(8);

        Byte handValue = data.get(accessor);
        byte handFlag = handValue == null ? 0 : handValue;

        handFlag = using ? (byte) (handFlag | 0x01) : (byte) (handFlag & ~0x01);
        data.set(accessor, handFlag);

        if(cachedViewers == null || cachedViewers.size() != npc.getViewers().size())
            updateCachedViewers(npc);

        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(serverPlayer.getId(), data.packAll());
        for(WeakReference<Player> viewer : cachedViewers)
            ((CraftPlayer) viewer.get()).getHandle().connection.send(packet);
    }

    /**
     * Performs a melee attack.
     */
    private void performMeleeAttack(@NotNull NPC npc)
    {
        if(target == null || !targetValid())
            return;

        if(cachedViewers == null || cachedViewers.size() != npc.getViewers().size())
            updateCachedViewers(npc);

        for(WeakReference<Player> viewer : cachedViewers)
            npc.playAnimation(viewer.get(), AnimatePacket.Animation.SWING_MAIN_HAND);

        LivingEntity victim = target;
        double damage = getAttackDamage(npc);
        double knockbackStrength = getAttackKnockback(npc);
        Location npcLoc = npc.getLocation().clone();

        runOnTarget(victim, () ->
        {
            victim.damage(damage);

            Location targetLoc = victim.getLocation();
            double knockbackResistance = getKnockbackResistance(victim);
            double effectiveKnockback = knockbackStrength * (1 - knockbackResistance);

            Vector direction = targetLoc.toVector().subtract(npcLoc.toVector());
            if(direction.lengthSquared() > Vector.getEpsilon())
                direction = direction.normalize();

            Vector knockback = direction.multiply(effectiveKnockback);
            knockback.setY(effectiveKnockback * 0.4);
            victim.setVelocity(knockback);
        });
    }

    /**
     * Checks if a material is a ranged weapon.
     */
    private boolean isRangedWeapon(@NotNull Material material)
    {
        return material == Material.BOW || material == Material.CROSSBOW || material == Material.TRIDENT;
    }

    /**
     * Checks if the NPC is currently using a ranged weapon.
     */
    private boolean isUsingRangedWeapon(@NotNull NPC npc)
    {
        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;
        return (mainHand != null && isRangedWeapon(mainHand.getType())) || getWeaponTitle(mainHand) != null;
    }

    /**
     * Gets the attack damage based on the NPC's held item.
     */
    private double getAttackDamage(@NotNull NPC npc)
    {
        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;
        return getAttackDamage(mainHand);
    }

    /**
     * Gets the attack damage from an item stack.
     *
     * @param item the item to check
     * @return the attack damage value
     */
    private double getAttackDamage(ItemStack item)
    {
        if(item == null || !item.hasItemMeta())
            return 0.5;

        ItemMeta meta = item.getItemMeta();
        if(meta == null)
            return 0.5;

        Collection<AttributeModifier> modifiers;
        try
        {
            //noinspection UnstableApiUsage
            modifiers = meta.getAttributeModifiers(Attribute.valueOf("GENERIC_ATTACK_DAMAGE"));
        }
        catch(Throwable e)
        {
            modifiers = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
        }

        if(modifiers == null)
            return 0.5;

        double damage = 0;
        for(AttributeModifier modifier : modifiers)
            damage += modifier.getAmount();

        return damage == 0 ? 0.5 : damage;
    }

    /**
     * Gets the attack knockback strength from the NPC's held item.
     *
     * @param npc the NPC to check
     * @return the knockback strength
     */
    private double getAttackKnockback(@NotNull NPC npc)
    {
        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;
        return getAttackKnockback(mainHand);
    }

    /**
     * Gets the attack knockback strength from an item stack.
     *
     * @param item the item to check
     * @return the knockback strength
     */
    private double getAttackKnockback(ItemStack item)
    {
        if(item == null || !item.hasItemMeta())
            return 0.5;

        ItemMeta meta = item.getItemMeta();
        if(meta == null)
            return 0.5;

        Collection<AttributeModifier> modifiers;
        try
        {
            //noinspection UnstableApiUsage
            modifiers = meta.getAttributeModifiers(Attribute.valueOf("GENERIC_ATTACK_KNOCKBACK"));
        }
        catch(Throwable e)
        {
            modifiers = meta.getAttributeModifiers(Attribute.ATTACK_KNOCKBACK);
        }

        if(modifiers == null)
            return 0.5;

        double knockback = 0;
        for(AttributeModifier modifier : modifiers)
            knockback += modifier.getAmount();

        return knockback == 0 ? 0.5 : knockback;
    }

    /**
     * Gets the knockback resistance of a target entity.
     *
     * @param target the entity to check
     * @return the knockback resistance value (0-1)
     */
    private double getKnockbackResistance(@NotNull LivingEntity target)
    {
        Attribute attribute;
        try
        {
            //noinspection UnstableApiUsage
            attribute = Attribute.valueOf("GENERIC_KNOCKBACK_RESISTANCE");
        }
        catch(Throwable e)
        {
            attribute = Attribute.KNOCKBACK_RESISTANCE;
        }

        if(target.getAttribute(attribute) == null)
            return 0;

        return target.getAttribute(attribute).getValue();
    }

    /**
     * Starts movement towards the target's ground location. The goal handles the look direction itself, so the walk does not send its own rotation.
     */
    private void startMovement(@NotNull NPC npc, @NotNull Location ground)
    {
        stopMovement(npc);

        movementGoal = new WalkToLocationGoal.Builder(ground.clone()).speed(speed).pathfinder(pathfinder).withRotation(false).build();
        movementGoal.start(npc);
    }

    /**
     * Starts kiting by moving away from the target to optimal distance. If a kiting walk is already running it is simply retargeted (no stop/start), which
     * keeps the retreat smooth.
     */
    private void startKiting(@NotNull NPC npc)
    {
        if(target == null || !targetValid())
            return;

        boolean retarget = isKiting && movementGoal != null && movementGoal.isActive();

        Location npcLoc = npc.getLocation();
        Location targetLoc = targetPosition();
        if(targetLoc == null)
            return;

        Vector away = npcLoc.toVector().subtract(targetLoc.toVector());
        away.setY(0);
        if(away.lengthSquared() < 1.0E-6)
        {
            double angle = Math.random() * Math.PI * 2;
            away = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        else
            away.normalize();

        Location retreatLoc = null;
        Location fallback = null;
        for(double angle : new double[]{0, 0.7, -0.7, 1.4, -1.4})
        {
            Vector pos = targetLoc.toVector().add(away.clone().rotateAroundY(angle).multiply(OPTIMAL_RANGED_DISTANCE));
            if(!retarget && angle == 0)
            {
                pos.setX(pos.getX() + (Math.random() - 0.5) * 3);
                pos.setZ(pos.getZ() + (Math.random() - 0.5) * 3);
            }

            Location candidate = new Location(npcLoc.getWorld(), pos.getX(), npcLoc.getY(), pos.getZ());
            if(fallback == null)
                fallback = candidate.clone();

            if(candidate.getWorld() == null || !WorldAccess.canAccessArea(candidate.getWorld(), candidate.getBlockX() - 1, candidate.getBlockZ() - 1,
                    candidate.getBlockX() + 1, candidate.getBlockZ() + 1))
                continue;

            OptionalInt safeY = LocationUtils.findSafeY(candidate, npc);
            if(safeY.isPresent())
            {
                candidate.setY(safeY.getAsInt());
                retreatLoc = candidate;
                break;
            }
        }
        if(retreatLoc == null)
            retreatLoc = fallback;

        if(retarget)
            movementGoal.updateTargetLocation(retreatLoc, npc);
        else
        {
            stopMovement(npc);

            WalkToLocationGoal newGoal = new WalkToLocationGoal.Builder(retreatLoc)
                    .speed(speed + 0.5)
                    .pathfinder(pathfinder)
                    .maxIterations(5000)
                    .allowDiagonal(true)
                    .completionCallback(walkingResult ->
                    {
                        isKiting = false;
                        movementGoal = null;
                    }).withRotation(false).build();

            movementGoal = newGoal;
            newGoal.start(npc);
        }

        isKiting = true;
        pathRecalculationCooldown = WalkToLocationGoal.RECALCULATION_COOLDOWN;
    }

    /**
     * Stops kiting and prepares to attack.
     */
    private void stopKiting(@NotNull NPC npc)
    {
        isKiting = false;
        stopMovement(npc);
        attackCooldown = 0;
    }

    /**
     * Updates the cached viewers list.
     */
    private void updateCachedViewers(@NotNull NPC npc)
    {
        cachedViewers = npc.getViewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .map(WeakReference::new)
                .toList();
    }

    /**
     * Stops any ongoing movement.
     */
    private void stopMovement(@NotNull NPC npc)
    {
        if(movementGoal != null)
        {
            movementGoal.stop(npc);
            movementGoal = null;
        }
    }

    /**
     * Gson JSON adapter for serializing and deserializing entity targeting predicates.
     */
    private static class TargetFilterAdapter
            implements JsonSerializer<SerializableBiPredicate<LivingEntity, NPC>>, JsonDeserializer<SerializableBiPredicate<LivingEntity, NPC>>
    {
        @Override
        public JsonElement serialize(SerializableBiPredicate<LivingEntity, NPC> src, Type typeOfSrc, JsonSerializationContext context)
        {
            if(src == null)
                return JsonNull.INSTANCE;

            if(src instanceof RegistryPredicate predicate)
            {
                JsonObject obj = new JsonObject();
                obj.addProperty(predicate.key(), predicate.expression() != null ? predicate.expression() : "");
                return obj;
            }

            String expression = src.toString();
            if(expression != null && !expression.contains("$$Lambda") && !expression.contains("@"))
            {
                JsonObject obj = new JsonObject();
                obj.addProperty("expression", expression);
                return obj;
            }

            return JsonNull.INSTANCE;
        }

        @SuppressWarnings("unchecked")
        @Override
        public SerializableBiPredicate<LivingEntity, NPC> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException
        {
            if(json == null || json.isJsonNull())
                return null;

            if(json.isJsonPrimitive())
                return new RegistryPredicate<>(NpcRegistry.EXPRESSION_PARSER, json.getAsString());

            JsonObject obj = json.getAsJsonObject();

            if(obj.has("key"))
            {
                String key = obj.get("key").getAsString();
                String expression = obj.has("expression") ? obj.get("expression").getAsString() : null;
                return new RegistryPredicate<>(key, expression);
            }

            if(obj.entrySet().size() == 1)
            {
                var entry = obj.entrySet().iterator().next();
                String key = entry.getKey();
                JsonElement val = entry.getValue();

                String expression = (val != null && !val.isJsonNull() && !val.getAsString().isEmpty()) ? val.getAsString() : null;
                return new RegistryPredicate<>(key, expression);
            }

            if(obj.has("lambda") || obj.has("lamda"))
            {
                String base64Str = obj.has("lambda") ? obj.get("lambda").getAsString() : obj.get("lamda").getAsString();
                try
                {
                    byte[] data = Base64.getDecoder().decode(base64Str);
                    try(ByteArrayInputStream bais = new ByteArrayInputStream(data);
                        ObjectInputStream ois = new ObjectInputStream(bais))
                    {
                        return (SerializableBiPredicate<LivingEntity, NPC>) ois.readObject();
                    }
                }
                catch(Exception ignored) {}
            }

            return null;
        }
    }
}
