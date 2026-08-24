package de.minecraftgilde.farmwelt.reset;

import io.papermc.paper.ServerBuildInfo;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Restricts version-sensitive DragonBattle saved-data and runtime access to reviewed versions. */
final class EndDragonFightCompatibility {

    static final String SUPPORTED_MINECRAFT_VERSION = "26.1.2";
    private static final Set<String> SUPPORTED_MINECRAFT_VERSIONS =
            Set.of(SUPPORTED_MINECRAFT_VERSION);

    private final Supplier<String> minecraftVersion;

    EndDragonFightCompatibility(Supplier<String> minecraftVersion) {
        this.minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
    }

    static EndDragonFightCompatibility runningServer() {
        return new EndDragonFightCompatibility(
                () -> ServerBuildInfo.buildInfo().minecraftVersionId()
        );
    }

    boolean isSupported() {
        return SUPPORTED_MINECRAFT_VERSIONS.contains(currentMinecraftVersion());
    }

    void requireSupported() {
        String currentVersion = currentMinecraftVersion();
        if (SUPPORTED_MINECRAFT_VERSIONS.contains(currentVersion)) {
            return;
        }
        throw new IllegalStateException(
                "DragonBattle-Kompatibilitätszugriff ist f\u00fcr Minecraft-Version '"
                        + currentVersion + "' nicht freigegeben. Unterst\u00fctzte Version: "
                        + SUPPORTED_MINECRAFT_VERSION
        );
    }

    private String currentMinecraftVersion() {
        return Objects.requireNonNull(
                minecraftVersion.get(),
                "Minecraft-Version aus ServerBuildInfo"
        );
    }
}
