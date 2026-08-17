package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.util.Map;

public interface ResetStateRepository {

    Map<String, FarmworldResetState> load() throws IOException;

    void save(Map<String, FarmworldResetState> states) throws IOException;
}
