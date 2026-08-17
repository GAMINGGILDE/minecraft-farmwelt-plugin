package de.minecraftgilde.farmwelt.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetExecutor;
import de.minecraftgilde.farmwelt.reset.FarmworldResetService;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import de.minecraftgilde.farmwelt.reset.ResetResult;
import de.minecraftgilde.farmwelt.reset.ResetOptions;
import de.minecraftgilde.farmwelt.reset.ResetStateRepository;
import de.minecraftgilde.farmwelt.reset.ResetStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FarmweltAdminCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");

    private final FarmworldResetService resetService = new FarmworldResetService(
            new InMemoryRepository(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            quietLogger()
    );
    private final FakeResetExecutor resetExecutor = new FakeResetExecutor();
    private final AtomicInteger reloadCount = new AtomicInteger();
    private final FarmweltAdminCommandHandler handler = new FarmweltAdminCommandHandler(
            resetService,
            resetExecutor,
            new FarmworldStatusFormatter(Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC),
            reloadCount::incrementAndGet,
            quietLogger()
    );

    @BeforeEach
    void configureWorlds() {
        resetService.reload(List.of(
                new FarmworldResetConfig("overworld", "test_farmwelt", true, Duration.ofDays(30)),
                new FarmworldResetConfig("nether", "test_netherfarm", false, Duration.ofDays(30)),
                new FarmworldResetConfig("end", "test_endfarm", true, Duration.ofDays(60))
        ));
        resetExecutor.future = new CompletableFuture<>();
        resetExecutor.lastKey = null;
        resetExecutor.lastOptions = null;
        reloadCount.set(0);
    }

    @Test
    void routesStatusDetailsReloadAndForceReset() {
        TestAudience audience = TestAudience.withPermissions(
                FarmweltAdminCommandHandler.STATUS_PERMISSION,
                FarmweltAdminCommandHandler.RELOAD_PERMISSION,
                FarmweltAdminCommandHandler.RESET_PERMISSION
        );

        assertTrue(handler.handle(audience, new String[]{"status"}));
        assertTrue(audience.contains("Farmwelt Reset-Status"));

        audience.clear();
        assertTrue(handler.handle(audience, new String[]{"status", "overworld"}));
        assertTrue(audience.contains("Reset-Status: §eoverworld"));
        assertTrue(audience.contains("Weltname: §ftest_farmwelt"));

        audience.clear();
        assertTrue(handler.handle(audience, new String[]{"reload"}));
        assertEquals(1, reloadCount.get());
        assertTrue(audience.contains("wurde neu geladen"));

        audience.clear();
        assertTrue(handler.handle(audience, new String[]{"reset", "force", "overworld"}));
        assertEquals("overworld", resetExecutor.lastKey);
        assertTrue(audience.contains("wurde gestartet"));
    }

    @Test
    void invalidRoutesNeverThrowAndReturnUsefulUsageOrWorldLists() {
        TestAudience audience = TestAudience.withPermissions(
                FarmweltAdminCommandHandler.STATUS_PERMISSION,
                FarmweltAdminCommandHandler.RESET_PERMISSION
        );

        for (String[] args : List.of(
                new String[]{"reset"},
                new String[]{"reset", "foo"},
                new String[]{"reset", "force"}
        )) {
            audience.clear();
            assertDoesNotThrow(() -> handler.handle(audience, args));
            assertTrue(audience.contains("Verwendung: /farmwelt reset force <welt>"));
        }

        audience.clear();
        assertDoesNotThrow(() -> handler.handle(
                audience,
                new String[]{"reset", "force", "missing"}
        ));
        assertTrue(audience.contains("nicht konfiguriert"));
        assertTrue(audience.contains("overworld, nether, end"));
        assertEquals(null, resetExecutor.lastKey);

        audience.clear();
        assertDoesNotThrow(() -> handler.handle(audience, new String[]{"status", "missing"}));
        assertTrue(audience.contains("Unbekannte Farmwelt 'missing'"));
    }

    @Test
    void permissionsAreIndependent() {
        TestAudience statusAudience = TestAudience.withPermissions(
                FarmweltAdminCommandHandler.STATUS_PERMISSION
        );

        handler.handle(statusAudience, new String[]{"status"});
        assertTrue(statusAudience.contains("Farmwelt Reset-Status"));

        statusAudience.clear();
        handler.handle(statusAudience, new String[]{"reload"});
        assertTrue(statusAudience.contains("keine Berechtigung"));
        assertEquals(0, reloadCount.get());

        statusAudience.clear();
        handler.handle(statusAudience, new String[]{"reset", "force", "overworld"});
        assertTrue(statusAudience.contains("keine Berechtigung"));
        assertEquals(null, resetExecutor.lastKey);
    }

    @Test
    void forceResetReturnsImmediatelyAndSendsCompletionLater() {
        TestAudience audience = TestAudience.withPermissions(FarmweltAdminCommandHandler.RESET_PERMISSION);

        handler.handle(audience, new String[]{"reset", "force", "overworld"});

        assertFalse(resetExecutor.future.isDone());
        assertEquals(1, audience.messages.size());
        assertTrue(audience.contains("wurde gestartet"));

        resetExecutor.future.complete(new ResetResult(
                "overworld",
                "test_farmwelt",
                ResetStatus.SUCCESS,
                "erfolgreich",
                null
        ));

        assertEquals(2, audience.messages.size());
        assertTrue(audience.contains("erfolgreich zurückgesetzt"));
    }

    @Test
    void tabCompletionUsesConfiguredLogicalIdsAndPermissions() {
        TestAudience statusOnly = TestAudience.withPermissions(FarmweltAdminCommandHandler.STATUS_PERMISSION);

        assertEquals(List.of("status"), handler.suggest(statusOnly, new String[]{""}));
        assertEquals(
                List.of("overworld", "nether", "end"),
                handler.suggest(statusOnly, new String[]{"status", ""})
        );
        assertTrue(handler.suggest(statusOnly, new String[]{"reset", ""}).isEmpty());

        TestAudience resetOnly = TestAudience.withPermissions(FarmweltAdminCommandHandler.RESET_PERMISSION);
        assertEquals(List.of("force"), handler.suggest(resetOnly, new String[]{"reset", ""}));
        assertEquals(
                List.of("overworld", "nether", "end"),
                handler.suggest(resetOnly, new String[]{"reset", "force", ""})
        );
        assertEquals(
                List.of("--dragon"),
                handler.suggest(resetOnly, new String[]{"reset", "force", "end", ""})
        );
        assertTrue(handler.suggest(
                resetOnly,
                new String[]{"reset", "force", "overworld", ""}
        ).isEmpty());
    }

    @Test
    void dragonOverrideIsOneTimeAndOnlyAcceptedForEndFarmworld() {
        TestAudience audience = TestAudience.withPermissions(FarmweltAdminCommandHandler.RESET_PERMISSION);

        handler.handle(audience, new String[]{"reset", "force", "end", "--dragon"});

        assertEquals("end", resetExecutor.lastKey);
        assertEquals(ResetOptions.allowingEnderDragon(), resetExecutor.lastOptions);

        resetExecutor.lastKey = null;
        resetExecutor.lastOptions = null;
        audience.clear();
        handler.handle(audience, new String[]{"reset", "force", "overworld", "--dragon"});

        assertEquals(null, resetExecutor.lastKey);
        assertEquals(null, resetExecutor.lastOptions);
        assertTrue(audience.contains("nur für eine End-Farmwelt"));
    }

    @Test
    void rejectsUnknownAdditionalResetArguments() {
        TestAudience audience = TestAudience.withPermissions(FarmweltAdminCommandHandler.RESET_PERMISSION);

        handler.handle(audience, new String[]{"reset", "force", "end", "--unknown"});

        assertEquals(null, resetExecutor.lastKey);
        assertTrue(audience.contains("Verwendung: /farmwelt reset force <welt>"));
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("FarmweltAdminCommandHandlerTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static final class FakeResetExecutor implements FarmworldResetExecutor {

        private CompletableFuture<ResetResult> future;
        private String lastKey;
        private ResetOptions lastOptions;

        @Override
        public CompletableFuture<ResetResult> reset(String farmworldKey) {
            lastKey = farmworldKey;
            return future;
        }

        @Override
        public CompletableFuture<ResetResult> reset(String farmworldKey, ResetOptions options) {
            lastKey = farmworldKey;
            lastOptions = options;
            return future;
        }

        @Override
        public boolean isResetRunning(String farmworldKey) {
            return false;
        }

        @Override
        public boolean isFarmworldAvailable(String farmworldKey) {
            return !isResetRunning(farmworldKey);
        }
    }

    private static final class TestAudience implements AdminCommandAudience {

        private final Set<String> permissions;
        private final List<String> messages = new ArrayList<>();

        private TestAudience(Set<String> permissions) {
            this.permissions = permissions;
        }

        static TestAudience withPermissions(String... permissions) {
            return new TestAudience(Set.of(permissions));
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public String name() {
            return "TestAdmin";
        }

        @Override
        public void sendMessages(List<String> messages) {
            this.messages.addAll(messages);
        }

        boolean contains(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }

        void clear() {
            messages.clear();
        }
    }

    private static final class InMemoryRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states = new LinkedHashMap<>();

        @Override
        public Map<String, FarmworldResetState> load() {
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
        }
    }
}
