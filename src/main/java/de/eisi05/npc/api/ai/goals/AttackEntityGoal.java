package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.utils.LocationUtils;
import de.eisi05.npc.api.utils.SerializableBiPredicate;
import de.eisi05.npc.api.wrapper.packets.AnimatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.*;

/**
 * A goal that makes the NPC attack nearby entities that match a predicate. The attack behavior varies based on the item in the NPC's main hand: -
 * Bow/Trident/Crossbow: Long range attacks - Sword/Axe: Short range attacks - Other: Short range attacks Only activates when a valid target is in range and
 * line of sight. Once a target is locked, the NPC continues attacking until the target becomes unreachable or invalid.
 */
public class AttackEntityGoal extends Goal
{
    @Serial
    private static final long serialVersionUID = 1L;

    private static final double BOW_ATTACK_RANGE = 15.0;
    private static final double MELEE_ATTACK_RANGE = 3.0;
    private static final double LINE_OF_SIGHT_RANGE = 25.0;
    private static final int BOW_DRAW_DELAY_TICKS = 25;
    private static final double KITING_DISTANCE = 3.0;
    private static final double OPTIMAL_RANGED_DISTANCE = 6.0;

    private SerializableBiPredicate<LivingEntity, NPC> targetFilter;
    private double customAttackRange;
    private double speed;

    private transient LivingEntity target;
    private transient int attackCooldown;
    private transient boolean isAttacking;
    private transient WalkToLocationGoal movementGoal;
    private transient boolean isUsing;
    private transient List<Player> cachedViewers;
    private transient int lineOfSightCheckCooldown;
    private transient int pathRecalculationCooldown;
    private transient boolean isKiting;

    /**
     * Creates an AttackEntityGoal with a filter for valid targets.
     *
     * @param targetFilter A predicate to filter valid targets
     */
    public AttackEntityGoal(@NotNull SerializableBiPredicate<LivingEntity, NPC> targetFilter)
    {
        this(targetFilter, -1);
    }

    /**
     * Creates an AttackEntityGoal with a filter for valid targets and custom attack range.
     *
     * @param targetFilter      A predicate to filter valid targets
     * @param customAttackRange Custom attack range (-1 for default based on weapon)
     */
    public AttackEntityGoal(@NotNull SerializableBiPredicate<LivingEntity, NPC> targetFilter, double customAttackRange)
    {
        this(targetFilter, customAttackRange, WalkToLocationGoal.DEFAULT_SPEED);
    }

    /**
     * Creates an AttackEntityGoal with a filter for valid targets and custom speed.
     *
     * @param targetFilter A predicate to filter valid targets
     * @param speed        Custom movement speed (-1 for default)
     */
    public AttackEntityGoal(@NotNull SerializableBiPredicate<LivingEntity, NPC> targetFilter, double customAttackRange, double speed)
    {
        super(Priority.ALWAYS);
        this.targetFilter = targetFilter;
        this.customAttackRange = customAttackRange;
        this.speed = Math.max(0.1, Math.min(1.0, speed));
    }

    /**
     * Creates a copy of this goal.
     *
     * @param goal the goal to copy
     */
    private AttackEntityGoal(@NotNull AttackEntityGoal goal)
    {
        super(goal.getPriority());
        this.targetFilter = goal.targetFilter;
        this.customAttackRange = goal.customAttackRange;
        this.speed = goal.speed;
    }

    /**
     * Gets the target filter for this goal.
     *
     * @return the target filter
     */
    public SerializableBiPredicate<LivingEntity, NPC> getTargetFilter()
    {
        return targetFilter;
    }

    /**
     * Sets the target filter for this goal.
     *
     * @param targetFilter the new target filter
     */
    public void setTargetFilter(@NotNull SerializableBiPredicate<LivingEntity, NPC> targetFilter)
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

        if(target != null && target.isValid() && canContinue(npc))
            return true;

        LivingEntity potentialTarget = findTarget(npc);
        if(potentialTarget == null)
            return false;

        this.target = potentialTarget;
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
        lineOfSightCheckCooldown = 0;
        pathRecalculationCooldown = 0;
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
        if(target == null || !target.isValid())
        {
            isAttacking = false;
            stopMovement(npc);
            return;
        }

        Location npcLoc = npc.getLocation();

        if(cachedViewers == null || cachedViewers.size() != npc.getViewers().size())
            updateCachedViewers(npc);

        for(Player viewer : cachedViewers)
            npc.lookAtEntity(target, viewer, true);

        if(lineOfSightCheckCooldown > 0)
            lineOfSightCheckCooldown--;

        double distance = npcLoc.distance(target.getLocation());
        double attackRange = getAttackRange(npc);

        if(distance > attackRange)
        {
            boolean checkLineOfSight = lineOfSightCheckCooldown <= 0;
            if(checkLineOfSight)
                lineOfSightCheckCooldown = 5;

            if(distance > LINE_OF_SIGHT_RANGE || (checkLineOfSight && !hasLineOfSight(npc, target)))
            {
                stop(npc);
                return;
            }

            if(movementGoal == null)
                startMovement(npc, target.getLocation());
            else
            {
                Location currentTarget = movementGoal.getTargetLocation();
                boolean shouldRecalculate = currentTarget.distance(target.getLocation()) > 5.0;

                if(!shouldRecalculate && pathRecalculationCooldown <= 0)
                {
                    shouldRecalculate = true;
                    pathRecalculationCooldown = WalkToLocationGoal.RECALCULATION_COOLDOWN;
                }

                if(shouldRecalculate)
                {
                    startMovement(npc, target.getLocation());
                    pathRecalculationCooldown = WalkToLocationGoal.RECALCULATION_COOLDOWN;
                }
                else
                {
                    movementGoal.tick(npc);
                    pathRecalculationCooldown--;
                }

                distance = npc.getLocation().distance(target.getLocation());
                if(distance <= attackRange)
                    stopMovement(npc);
                else
                    return;
            }
        }
        else if(isUsingRangedWeapon(npc))
        {
            if(distance < KITING_DISTANCE && !isKiting)
                startKiting(npc);
            else if(isKiting && distance >= OPTIMAL_RANGED_DISTANCE)
                stopKiting(npc);
            else if(isKiting)
            {
                if(movementGoal != null)
                {
                    movementGoal.tick(npc);
                    pathRecalculationCooldown--;
                    if(pathRecalculationCooldown <= 0)
                    {
                        startKiting(npc);
                        pathRecalculationCooldown = WalkToLocationGoal.RECALCULATION_COOLDOWN;
                    }
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
        target = null;
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
        if(!isAttacking || target == null || !target.isValid())
            return false;

        Location npcLoc = npc.getLocation();
        Location targetLoc = target.getLocation();

        if(!npcLoc.getWorld().equals(targetLoc.getWorld()))
            return false;

        boolean checkLineOfSight = lineOfSightCheckCooldown <= 0;
        if(checkLineOfSight && !hasLineOfSight(npc, target))
            return false;

        double distance = npcLoc.distance(targetLoc);
        double attackRange = getAttackRange(npc);

        return distance <= Math.max(attackRange, LINE_OF_SIGHT_RANGE);
    }

    @Override
    protected boolean canBeRemovedNow(@NotNull NPC npc)
    {
        if(super.canBeRemovedNow(npc))
            return true;

        if(movementGoal != null && movementGoal.canContinue(npc))
        {
            Location npcLoc = npc.getLocation();
            Location below = npcLoc.clone().subtract(0, 0.1, 0);

            if(below.getBlock().getType().isAir())
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
        Location npcLoc = npc.getLocation();
        double searchRange = Math.max(getAttackRange(npc), LINE_OF_SIGHT_RANGE);

        for(Entity entity : npcLoc.getWorld().getNearbyEntities(npcLoc, searchRange, searchRange, searchRange))
        {
            if(!(entity instanceof LivingEntity livingEntity))
                continue;

            if(!targetFilter.test(livingEntity, npc))
                continue;

            if(!hasLineOfSight(npc, livingEntity))
                continue;

            return livingEntity;
        }

        return null;
    }

    /**
     * Checks if the NPC has line of sight to the target entity.
     */
    private boolean hasLineOfSight(@NotNull NPC npc, @NotNull LivingEntity target)
    {
        Location npcLoc = npc.getLocation().clone().add(0, 1.6, 0);
        Location targetLoc = target.getLocation().clone().add(0, target.getEyeHeight(), 0);

        Vector direction = targetLoc.toVector().subtract(npcLoc.toVector()).normalize();
        double distance = npcLoc.distance(targetLoc);
        int steps = (int) (distance * 2);

        for(int i = 0; i < steps; i++)
        {
            Location checkLoc = npcLoc.clone().add(direction.clone().multiply(i * 0.5));
            if(checkLoc.getBlock().getType().isSolid() && !checkLoc.getBlock().isPassable())
                return false;
        }

        return true;
    }

    /**
     * Gets the attack range based on the NPC's held item.
     */
    private double getAttackRange(@NotNull NPC npc)
    {
        if(customAttackRange > 0)
            return customAttackRange;

        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;

        if(mainHand == null)
            return MELEE_ATTACK_RANGE;

        Material type = mainHand.getType();
        if(isRangedWeapon(type))
            return BOW_ATTACK_RANGE;
        else
            return MELEE_ATTACK_RANGE;
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

        ItemMeta meta = mainHand.getItemMeta();
        if(meta == null)
            return 5;

        Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(Attribute.ATTACK_SPEED);
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

        if(mainHand != null && isRangedWeapon(mainHand.getType()))
            performRangedAttack(npc);
        else
            performMeleeAttack(npc);
    }

    /**
     * Performs a ranged attack.
     */
    private void performRangedAttack(@NotNull NPC npc)
    {
        if(target == null || !target.isValid() || isUsing)
            return;

        net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) npc.getEntity();
        double eyeHeight = (entity.getBukkitEntity() instanceof LivingEntity le ? le.getEyeHeight() : entity.getBukkitEntity().getHeight()) -
                (npc.getOption(NpcOption.POSE) == org.bukkit.entity.Pose.SITTING ? 0.625 : 0);

        Location npcLoc = npc.getLocation().clone().add(0, eyeHeight * npc.getOption(NpcOption.SCALE), 0);

        final float speed = 2.5f;

        Map<EquipmentSlot, ItemStack> equipment = npc.getOption(NpcOption.EQUIPMENT);
        ItemStack mainHand = equipment != null ? equipment.get(EquipmentSlot.HAND) : null;

        if(mainHand != null && (mainHand.getType() == Material.BOW || mainHand.getType() == Material.CROSSBOW))
        {
            setUsingItemState(npc, true);
            isUsing = true;

            Bukkit.getScheduler().runTaskLater(NpcApi.plugin, () ->
            {
                setUsingItemState(npc, false);

                isUsing = false;

                if(target == null)
                    return;

                Location targetLoc = target.getLocation().clone().add(0, target.getEyeHeight(), 0);
                Vector direction = targetLoc.toVector().subtract(npcLoc.toVector()).normalize();

                AbstractArrow arrow;
                if(mainHand == null || mainHand.getType() == Material.TRIDENT)
                    arrow = npcLoc.getWorld().spawnArrow(npcLoc, direction, speed, 0, Trident.class);
                else
                    arrow = npcLoc.getWorld().spawnArrow(npcLoc, direction, speed, 0, Arrow.class);
                arrow.setShooter(((ServerPlayer) npc.getServerPlayer()).getBukkitEntity());
                arrow.setDamage(getAttackDamage(npc));
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                arrow.setWeapon(mainHand);

                ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(arrow.getEntityId());
                for(Player player : npcLoc.getWorld().getPlayers())
                {
                    if(!npc.getViewers().contains(player.getUniqueId()))
                        ((CraftPlayer) player).getHandle().connection.send(removePacket);
                }
            }, BOW_DRAW_DELAY_TICKS);
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
        for(Player viewer : cachedViewers)
            ((CraftPlayer) viewer).getHandle().connection.send(packet);
    }

    /**
     * Performs a melee attack.
     */
    private void performMeleeAttack(@NotNull NPC npc)
    {
        if(target == null || !target.isValid())
            return;

        if(cachedViewers == null || cachedViewers.size() != npc.getViewers().size())
            updateCachedViewers(npc);

        for(Player viewer : cachedViewers)
            npc.playAnimation(viewer, AnimatePacket.Animation.SWING_MAIN_HAND);

        target.damage(getAttackDamage(npc));

        Location npcLoc = npc.getLocation();
        Location targetLoc = target.getLocation();

        double knockbackStrength = getAttackKnockback(npc);
        double knockbackResistance = getKnockbackResistance(target);
        double effectiveKnockback = knockbackStrength * (1 - knockbackResistance);

        Vector direction = targetLoc.toVector().subtract(npcLoc.toVector());
        if(direction.lengthSquared() > Vector.getEpsilon())
            direction = direction.normalize();

        Vector knockback = direction.multiply(effectiveKnockback);
        knockback.setY(effectiveKnockback * 0.4);
        target.setVelocity(knockback);
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
        return mainHand != null && isRangedWeapon(mainHand.getType());
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

        Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
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

        Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(Attribute.ATTACK_KNOCKBACK);
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
        if(target.getAttribute(Attribute.KNOCKBACK_RESISTANCE) == null)
            return 0;

        return target.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue();
    }

    /**
     * Starts movement towards the target location.
     */
    private void startMovement(@NotNull NPC npc, @NotNull Location targetLoc)
    {
        stopMovement(npc);

        OptionalInt safeY = LocationUtils.findSafeY(targetLoc);
        if(safeY.isPresent())
            targetLoc = new Location(targetLoc.getWorld(), targetLoc.getX(), safeY.getAsInt(), targetLoc.getZ());
        movementGoal = new WalkToLocationGoal.Builder(targetLoc).speed(speed).build();
        movementGoal.start(npc);
    }

    /**
     * Starts kiting by moving away from the target to optimal distance.
     */
    private void startKiting(@NotNull NPC npc)
    {
        if(target == null || !target.isValid())
            return;

        Location npcLoc = npc.getLocation();
        Location targetLoc = target.getLocation();

        Vector direction = npcLoc.toVector().subtract(targetLoc.toVector()).normalize();

        Vector retreatPos = targetLoc.toVector().add(direction.multiply(OPTIMAL_RANGED_DISTANCE));

        retreatPos.setX(retreatPos.getX() + (Math.random() - 0.5) * 3);
        retreatPos.setZ(retreatPos.getZ() + (Math.random() - 0.5) * 3);

        Location retreatLoc = new Location(npcLoc.getWorld(), retreatPos.getX(), npcLoc.getY(), retreatPos.getZ());

        OptionalInt safeY = LocationUtils.findSafeY(retreatLoc);
        if(safeY.isPresent())
            retreatLoc = new Location(retreatLoc.getWorld(), retreatLoc.getX(), safeY.getAsInt(), retreatLoc.getZ());

        stopMovement(npc);


        WalkToLocationGoal newGoal = new WalkToLocationGoal.Builder(retreatLoc).speed(speed + 0.5).maxIterations(5000).allowDiagonal(true)
                .completionCallback(walkingResult ->
                {
                    isKiting = false;
                    movementGoal = null;
                }).withRotation(false).build();

        movementGoal = newGoal;
        newGoal.start(npc);

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
}
