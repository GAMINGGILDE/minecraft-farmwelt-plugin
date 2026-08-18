package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.math.Position;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.junit.jupiter.api.Test;

class EndDragonFightRuntimeAccessTest {

    @Test
    void endsLoadedFightAndRebuildsActivePortal() {
        FakeDragonFight fight = new FakeDragonFight();
        FakeDragonBattle battle = new FakeDragonBattle(fight);
        EndDragonFightRuntimeAccess access = EndDragonFightRuntimeAccess.reflective(
                FakeDragonBattle.class.getName(),
                FakeDragonFight.class.getName()
        );

        access.suppress(battle);

        assertTrue(fight.dragonKilled);
        assertTrue(fight.hasPreviouslyKilledDragon);
        assertFalse(fight.needsStateScanning);
        assertNull(fight.dragonUUID);
        assertNull(fight.respawnStage);
        assertNotNull(fight.respawnCrystals);
        assertTrue(fight.activePortal);
        assertTrue(fight.dirty);
        assertFalse(battle.bossBarVisible.get());
    }

    @Test
    void restoresFreshInitialFightAfterSuppression() {
        FakeDragonFight fight = new FakeDragonFight();
        fight.dragonKilled = true;
        fight.hasPreviouslyKilledDragon = true;
        fight.needsStateScanning = false;
        fight.respawnStage = new Object();
        FakeDragonBattle battle = new FakeDragonBattle(fight);
        battle.bossBarVisible.set(false);
        EndDragonFightRuntimeAccess access = EndDragonFightRuntimeAccess.reflective(
                FakeDragonBattle.class.getName(),
                FakeDragonFight.class.getName()
        );

        access.prepareInitialFight(battle);

        assertFalse(fight.dragonKilled);
        assertFalse(fight.hasPreviouslyKilledDragon);
        assertTrue(fight.needsStateScanning);
        assertNull(fight.dragonUUID);
        assertNull(fight.respawnStage);
        assertNotNull(fight.respawnCrystals);
        assertTrue(fight.portalPresent);
        assertFalse(fight.activePortal);
        assertTrue(fight.dirty);
        assertTrue(battle.bossBarVisible.get());
    }

    @Test
    void reportsMissingExitPortalLocationClearly() {
        FakeDragonFight fight = new FakeDragonFight();
        fight.portalGenerationSucceeds = false;
        FakeDragonBattle battle = new FakeDragonBattle(fight);
        EndDragonFightRuntimeAccess access = EndDragonFightRuntimeAccess.reflective(
                FakeDragonBattle.class.getName(),
                FakeDragonFight.class.getName()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> access.suppress(battle)
        );

        assertTrue(exception.getMessage().contains(
                "Aktives End-Ausgangsportal konnte nicht verifiziert werden"
        ));
    }

    private static final class FakeDragonBattle implements DragonBattle {

        private final FakeDragonFight handle;
        private final AtomicBoolean bossBarVisible = new AtomicBoolean(true);
        private final BossBar bossBar = (BossBar) Proxy.newProxyInstance(
                BossBar.class.getClassLoader(),
                new Class<?>[]{BossBar.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setVisible" -> {
                        bossBarVisible.set((Boolean) arguments[0]);
                        yield null;
                    }
                    case "isVisible" -> bossBarVisible.get();
                    default -> defaultValue(method.getReturnType());
                }
        );

        private FakeDragonBattle(FakeDragonFight handle) {
            this.handle = handle;
        }

        @Override
        public EnderDragon getEnderDragon() {
            return null;
        }

        @Override
        public BossBar getBossBar() {
            return bossBar;
        }

        @Override
        public Location getEndPortalLocation() {
            return handle.portalPresent ? new Location(null, 0, 64, 0) : null;
        }

        @Override
        public boolean generateEndPortal(boolean withPortals) {
            return false;
        }

        @Override
        public boolean hasBeenPreviouslyKilled() {
            return handle.hasPreviouslyKilledDragon;
        }

        @Override
        public void setPreviouslyKilled(boolean previouslyKilled) {
            handle.hasPreviouslyKilledDragon = previouslyKilled;
        }

        @Override
        public void initiateRespawn() {
        }

        @Override
        public boolean initiateRespawn(Collection<EnderCrystal> crystals) {
            return false;
        }

        @Override
        public RespawnPhase getRespawnPhase() {
            return RespawnPhase.NONE;
        }

        @Override
        public boolean setRespawnPhase(RespawnPhase phase) {
            return false;
        }

        @Override
        public void resetCrystals() {
        }

        @Override
        public int getGatewayCount() {
            return 0;
        }

        @Override
        public boolean spawnNewGateway() {
            return false;
        }

        @Override
        public void spawnNewGateway(Position position) {
        }

        @Override
        public List<EnderCrystal> getRespawnCrystals() {
            return List.of();
        }

        @Override
        public List<EnderCrystal> getHealingCrystals() {
            return List.of();
        }
    }

    private static final class FakeDragonFight {

        private boolean dragonKilled;
        public boolean hasPreviouslyKilledDragon;
        private boolean needsStateScanning = true;
        public UUID dragonUUID = UUID.randomUUID();
        public Object respawnStage = new Object();
        public Object respawnCrystals = new Object();
        private boolean portalPresent;
        private boolean activePortal;
        private boolean portalGenerationSucceeds = true;
        private boolean dirty;

        public void spawnExitPortal(boolean active) {
            if (!portalGenerationSucceeds) {
                return;
            }
            portalPresent = true;
            activePortal = active;
        }

        public void setDirty() {
            dirty = true;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
