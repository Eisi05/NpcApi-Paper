package de.eisi05.npc.api.manager;

import de.eisi05.npc.api.interfaces.NpcClickAction;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * Manages the combat-related state and death behavior of an NPC.
 *
 * <p>Handles health, knockback, death behavior, respawning, and respawn locations.</p>
 */
public class NpcCombatManager implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    private boolean enabled = false;
    private double maxHealth = 20.0; // -1 = invulnerable
    private double currentHealth = 20.0;
    private double knockbackMultiplier = 1.0;

    private NpcClickAction deathAction = null;

    /**
     * Creates a copy of this combat manager with the same configuration and current state.
     *
     * @return a copy of this combat manager
     */
    public @NotNull NpcCombatManager copy()
    {
        return new NpcCombatManager()
                .setMaxHealth(maxHealth)
                .setKnockbackMultiplier(knockbackMultiplier)
                .setDeathAction(deathAction)
                .setEnabled(enabled)
                .setCurrentHealth(currentHealth);
    }

    // --- Getters & Setters ---

    /**
     * Checks whether combat is enabled.
     *
     * @return {@code true} if combat is enabled
     */
    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Sets whether combat is enabled.
     *
     * @param enabled {@code true} to enable combat, {@code false} to disable
     */
    public @NotNull NpcCombatManager setEnabled(boolean enabled)
    {
        this.enabled = enabled;
        return this;
    }

    /**
     * Gets the NPC's current health.
     *
     * @return the current health
     */
    public double getCurrentHealth()
    {
        return currentHealth;
    }

    /**
     * Sets the NPC's current health.
     *
     * <p>If the resulting health is zero or below, the configured death behavior
     * is triggered when an NPC is provided.</p>
     *
     * @param currentHealth the new health value
     * @return this combat manager
     */
    public @NotNull NpcCombatManager setCurrentHealth(double currentHealth)
    {
        this.currentHealth = Math.min(currentHealth, maxHealth);
        return this;
    }

    /**
     * Gets the maximum health of the NPC.
     *
     * @return the maximum health
     */
    public double getMaxHealth()
    {
        return maxHealth;
    }

    /**
     * Sets the maximum health of the NPC.
     *
     * @param maxHealth the new maximum health
     * @return this combat manager
     */
    public @NotNull NpcCombatManager setMaxHealth(double maxHealth)
    {
        this.maxHealth = maxHealth;
        this.currentHealth = Math.min(this.currentHealth, maxHealth);
        return this;
    }

    /**
     * Checks whether the NPC is invulnerable.
     *
     * @return {@code true} if the maximum health is negative
     */
    public boolean isInvulnerable()
    {
        return maxHealth < 0;
    }

    /**
     * Gets the knockback multiplier applied to the NPC.
     *
     * @return the knockback multiplier
     */
    public double getKnockbackMultiplier()
    {
        return knockbackMultiplier;
    }

    /**
     * Sets the knockback multiplier applied to the NPC.
     *
     * @param knockbackMultiplier the new knockback multiplier
     * @return this combat manager
     */
    public @NotNull NpcCombatManager setKnockbackMultiplier(double knockbackMultiplier)
    {
        this.knockbackMultiplier = Math.max(knockbackMultiplier, 0);
        return this;
    }

    /**
     * Gets the action to be executed when the NPC dies.
     *
     * @return the death action
     */
    public @NotNull NpcClickAction getDeathAction()
    {
        return deathAction;
    }


    /**
     * Sets the action to be executed when the NPC dies.
     *
     * @param deathAction the new death action
     * @return this combat manager
     */
    public @NotNull NpcCombatManager setDeathAction(@NotNull NpcClickAction deathAction)
    {
        this.deathAction = deathAction;
        return this;
    }
}