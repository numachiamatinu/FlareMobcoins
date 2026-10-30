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

    // ------------------------------------------------------------------ stats, boosters, events
    // who = player uuid or "*" (whole server / network), key = "kills", "kills:ZOMBIE", "week:2026-W44", "booster:200" ...

    /** Every value of a player (or "*"). */
    default java.util.Map<String, Double> stats(String who) throws Exception {
        return new java.util.HashMap<>();
    }

    /** Adds to many values at once (who -&gt; key -&gt; amount to add). */
    default void addStats(java.util.Map<String, java.util.Map<String, Double>> deltas) throws Exception {
    }

    /** Sets one value; 0 removes it. */
    default void setStat(String who, String key, double value) throws Exception {
    }

    /** Highest values of one key, with the player names. */
    default List<Account> topStat(String key, int size) throws Exception {
        return new java.util.ArrayList<>();
    }

    /** Removes a value only if it is still at most max (an ended booster that nobody made longer meanwhile). */
    default void deleteStatIfAtMost(String who, String key, double max) throws Exception {
        setStat(who, key, 0);
    }

    /** Keeps the highest value (best combo of a player on several servers). */
    default void maxStat(String who, String key, double value) throws Exception {
        setStat(who, key, value);
    }

    /** Reads and removes a value at once: only one caller gets it (notes). @return the value, -1 if there was none. */
    default double takeStat(String who, String key) throws Exception {
        return -1;
    }

    /** Sets many values in one go (/mobcoins migrate). */
    default void setStats(java.util.Map<String, java.util.Map<String, Double>> values) throws Exception {
        for (java.util.Map.Entry<String, java.util.Map<String, Double>> who : values.entrySet()) {
            for (java.util.Map.Entry<String, Double> e : who.getValue().entrySet()) setStat(who.getKey(), e.getKey(), e.getValue());
        }
    }

    /** Every value of every player (/mobcoins migrate). */
    default java.util.Map<String, java.util.Map<String, Double>> allStats() throws Exception {
        return new java.util.HashMap<>();
    }

    /** Removes every value whose key starts with prefix, except the keys in keep (old weeks and months). */
    default void deleteStats(String prefix, java.util.Collection<String> keep) throws Exception {
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
