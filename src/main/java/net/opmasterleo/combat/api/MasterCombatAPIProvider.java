package net.opmasterleo.combat.api;

public class MasterCombatAPIProvider {

    private static MasterCombatAPI API = null;

    public MasterCombatAPIProvider() {
    }

    public static MasterCombatAPI getAPI() {
        return API;
    }

    @Deprecated
    public static void register(MasterCombatAPI api) {
        API = api;
    }

    public static void set(MasterCombatAPIBackend apiBackend) {
        API = apiBackend;
    }
}