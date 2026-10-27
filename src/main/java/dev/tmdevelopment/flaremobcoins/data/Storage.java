package dev.tmdevelopment.flaremobcoins.data;

import java.util.List;
import java.util.UUID;

/** Where balances and multipliers are saved. Every method runs on the storage thread. */
public interface Storage {

    String type();

    void open() throws Exception;

    void close();

    /** The account, or null if the player has none. */
    Account get(UUID uuid) throws Exception;

    /** Creates the account with the start balance. @return true if it was new. */
    boolean create(UUID uuid, String name, double start) throws Exception;

    /** Adds (or removes, negative) mobcoins; never below 0. @return the new balance. */
    double add(UUID uuid, String name, double delta, double start) throws Exception;

    /** Removes mobcoins only if the player has enough. @return the new balance, -1 if he had less. */
    double take(UUID uuid, double amount) throws Exception;

    void set(UUID uuid, String name, double value) throws Exception;

    void setMultiplier(UUID uuid, String name, double multiplier, double start) throws Exception;

    void updateName(UUID uuid, String name) throws Exception;

    Account byName(String name) throws Exception;

    List<Account> top(int size) throws Exception;

    List<Account> all() throws Exception;

    int count() throws Exception;

    default void flush() throws Exception {
    }

    // ------------------------------------------------------------------ shop limits and stock
    // who = player uuid or "*" (whole server), item = "shop.item", period = "all", "2026-10-25", "2026-W43" ...

    /** How many times it was bought in this period. */
    default int bought(String who, String item, String period) throws Exception {
        return 0;
    }

    /** One more, only if it stays within max. @return false if the max was already reached. */
    default boolean reserve(String who, String item, String period, int max) throws Exception {
        return true;
    }

    /** One less (the purchase failed after the reserve). */
    default void release(String who, String item, String period) throws Exception {
    }

    final class Account {
        public final UUID uuid;
        public final String name;
        public final double mobcoins;
        public final double multiplier;

        public Account(UUID uuid, String name, double mobcoins, double multiplier) {
            this.uuid = uuid;
            this.name = name;
            this.mobcoins = mobcoins;
            this.multiplier = multiplier <= 0 ? 1 : multiplier;
        }
    }
}
