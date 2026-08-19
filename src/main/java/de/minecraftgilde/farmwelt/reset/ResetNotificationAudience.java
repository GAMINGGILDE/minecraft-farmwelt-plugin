package de.minecraftgilde.farmwelt.reset;

/** Kleine Ausgabeschnittstelle für serverweite Reset-Nachrichten. */
@FunctionalInterface
public interface ResetNotificationAudience {

    void broadcast(String message);
}
