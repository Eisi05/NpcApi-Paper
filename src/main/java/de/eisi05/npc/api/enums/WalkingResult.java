package de.eisi05.npc.api.enums;

import java.io.Serial;
import java.io.Serializable;

public enum WalkingResult implements Serializable
{
    SUCCESS,
    CANCELLED;

    @Serial
    private static final long serialVersionUID = 1L;
}
