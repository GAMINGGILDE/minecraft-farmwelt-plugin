package de.minecraftgilde.farmwelt.reset;

/** Immutable options that affect one reset invocation without changing its configuration. */
public record ResetOptions(boolean allowEnderDragon) {

    private static final ResetOptions DEFAULTS = new ResetOptions(false);

    public static ResetOptions defaults() {
        return DEFAULTS;
    }

    public static ResetOptions allowingEnderDragon() {
        return new ResetOptions(true);
    }
}
