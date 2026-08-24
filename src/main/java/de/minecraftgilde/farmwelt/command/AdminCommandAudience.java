package de.minecraftgilde.farmwelt.command;

import java.util.List;

interface AdminCommandAudience {

    boolean hasPermission(String permission);

    String name();

    void sendMessages(List<String> messages);

    default void sendMessage(String message) {
        sendMessages(List.of(message));
    }
}
