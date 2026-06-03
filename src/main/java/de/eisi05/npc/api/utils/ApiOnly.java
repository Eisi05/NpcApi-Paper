package de.eisi05.npc.api.utils;

import java.lang.annotation.*;

/**
 * Indicates that a configuration option or field is strictly reserved for third-party API integration and is not actively utilized by the internal plugin
 * logic.
 * <p/>
 * {@link de.eisi05.npc.api.objects.NpcConfig}
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ApiOnly
{
}
