package de.minecraftgilde.farmwelt.reset;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.bukkit.boss.DragonBattle;

/** Applies the parts of the End fight state that Bukkit's DragonBattle API cannot express. */
interface EndDragonFightRuntimeAccess {

    void suppress(DragonBattle battle);

    void prepareInitialFight(DragonBattle battle);

    static EndDragonFightRuntimeAccess runningServer() {
        return reflective(
                "org.bukkit.craftbukkit.boss.CraftDragonBattle",
                "net.minecraft.world.level.dimension.end.EnderDragonFight"
        );
    }

    static EndDragonFightRuntimeAccess reflective(
            String expectedCraftBattleType,
            String expectedFightType
    ) {
        Objects.requireNonNull(expectedCraftBattleType, "expectedCraftBattleType");
        Objects.requireNonNull(expectedFightType, "expectedFightType");
        return new EndDragonFightRuntimeAccess() {
            @Override
            public void suppress(DragonBattle battle) {
                applyReflectively(
                        battle,
                        expectedCraftBattleType,
                        expectedFightType,
                        true
                );
            }

            @Override
            public void prepareInitialFight(DragonBattle battle) {
                applyReflectively(
                        battle,
                        expectedCraftBattleType,
                        expectedFightType,
                        false
                );
            }
        };
    }

    private static void applyReflectively(
            DragonBattle battle,
            String expectedCraftBattleType,
            String expectedFightType,
            boolean suppress
    ) {
        Objects.requireNonNull(battle, "battle");
        try {
            Class<?> craftBattleType = requireType(
                    battle.getClass(),
                    expectedCraftBattleType
            );
            Field handleField = accessibleField(craftBattleType, "handle");
            Object fight = Objects.requireNonNull(
                    handleField.get(battle),
                    "CraftDragonBattle.handle ist null."
            );
            Class<?> fightType = requireType(
                    fight.getClass(),
                    expectedFightType
            );

            Field dragonKilled = accessibleField(fightType, "dragonKilled");
            Field previouslyKilled = accessibleField(fightType, "hasPreviouslyKilledDragon");
            Field needsStateScanning = accessibleField(fightType, "needsStateScanning");
            Field dragonUuid = accessibleField(fightType, "dragonUUID");
            Field respawnStage = accessibleField(fightType, "respawnStage");
            Method spawnExitPortal = accessibleMethod(fightType, "spawnExitPortal", boolean.class);
            Method setDirty = accessibleMethod(fightType, "setDirty");

            // The public API cannot switch the loaded fight between a fresh initial battle and
            // a completed one. Rebuilding the podium also works when an inactive portal was found.
            spawnExitPortal.invoke(fight, suppress);
            dragonKilled.setBoolean(fight, suppress);
            previouslyKilled.setBoolean(fight, suppress);
            needsStateScanning.setBoolean(fight, !suppress);
            dragonUuid.set(fight, null);
            respawnStage.set(fight, null);
            battle.getBossBar().setVisible(!suppress);
            setDirty.invoke(fight);

            if (dragonKilled.getBoolean(fight) != suppress
                    || previouslyKilled.getBoolean(fight) != suppress
                    || needsStateScanning.getBoolean(fight) == suppress
                    || battle.getBossBar().isVisible() == suppress
                    || battle.getEndPortalLocation() == null) {
                throw new IllegalStateException(
                        "Der DragonBattle-Laufzeitstatus konnte nicht verifiziert werden."
                );
            }
        } catch (IllegalAccessException | InvocationTargetException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null
                    ? invocation.getCause()
                    : exception;
            throw new IllegalStateException(
                    "DragonBattle-Laufzeitstatus konnte nicht gesetzt werden.",
                    cause
            );
        }
    }

    private static Class<?> requireType(Class<?> actual, String expectedName) {
        if (!actual.getName().equals(expectedName)) {
            throw new IllegalStateException(
                    "Nicht unterstützte DragonBattle-Implementierung: " + actual.getName()
            );
        }
        return actual;
    }

    private static Field accessibleField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            if (!field.trySetAccessible()) {
                throw new IllegalStateException(
                        "DragonBattle-Feld ist nicht zugänglich: " + type.getName() + "." + name
                );
            }
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException(
                    "DragonBattle-Feld fehlt: " + type.getName() + "." + name,
                    exception
            );
        }
    }

    private static Method accessibleMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            if (!method.trySetAccessible()) {
                throw new IllegalStateException(
                        "DragonBattle-Methode ist nicht zugänglich: " + type.getName() + "." + name
                );
            }
            return method;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "DragonBattle-Methode fehlt: " + type.getName() + "." + name,
                    exception
            );
        }
    }
}
