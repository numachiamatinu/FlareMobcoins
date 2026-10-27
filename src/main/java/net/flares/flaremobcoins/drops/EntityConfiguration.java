package net.flares.flaremobcoins.drops;

import java.util.Collections;
import java.util.List;

/** A drop of drops.yml, in the 3.x form (read only). */
public class EntityConfiguration {

    private final EntityGroup entityGroup;
    private final List<String> entities;
    private final List<String> requirement;
    private final List<String> dropRun;
    private final String dropValue;
    private final Byte dropChance;
    private final String configPath;

    public EntityConfiguration(EntityGroup group, List<String> entities, List<String> requirement, List<String> dropRun,
                               String dropValue, double dropChance, String configPath) {
        this.entityGroup = group;
        this.entities = entities == null ? Collections.<String>emptyList() : entities;
        this.requirement = requirement;
        this.dropRun = dropRun == null ? Collections.<String>emptyList() : dropRun;
        this.dropValue = dropValue;
        this.dropChance = (byte) Math.max(0, Math.min(100, Math.round(dropChance)));
        this.configPath = configPath;
    }

    public EntityGroup getEntityGroup() {
        return entityGroup;
    }

    public List<String> getEntities() {
        return entities;
    }

    public List<String> getRequirement() {
        return requirement;
    }

    public List<String> getDropRun() {
        return dropRun;
    }

    public String getDropValue() {
        return dropValue;
    }

    public Byte getDropChance() {
        return dropChance;
    }

    public String getConfigPath() {
        return configPath;
    }

    public enum EntityGroup {
        LIST, HOSTILE, PASSIVE, ALL
    }
}
