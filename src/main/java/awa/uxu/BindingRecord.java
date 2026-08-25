package awa.uxu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

public final class BindingRecord {
    private final UUID id;
    private UUID ownerUuid;
    private String ownerName;
    private ItemStack item;
    private boolean locked;
    private BindingLocation location;
    private final long createdAt;
    private long updatedAt;

    public BindingRecord(UUID id, UUID ownerUuid, String ownerName, ItemStack item, boolean locked, BindingLocation location, long createdAt, long updatedAt) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.item = item;
        this.locked = locked;
        this.location = location;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        if (Objects.equals(this.ownerUuid, ownerUuid)) {
            return;
        }
        this.ownerUuid = ownerUuid;
        touch();
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        if (Objects.equals(this.ownerName, ownerName)) {
            return;
        }
        this.ownerName = ownerName;
        touch();
    }

    public ItemStack getItem() {
        return item;
    }

    public void setItem(ItemStack item) {
        if (Objects.equals(this.item, item)) {
            return;
        }
        this.item = item;
        touch();
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        if (this.locked == locked) {
            return;
        }
        this.locked = locked;
        touch();
    }

    public BindingLocation getLocation() {
        return location;
    }

    public void setLocation(BindingLocation location) {
        if (Objects.equals(this.location, location)) {
            return;
        }
        this.location = location;
        touch();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        long now = System.currentTimeMillis();
        this.updatedAt = Math.max(now, this.updatedAt + 1L);
    }

    public void save(ConfigurationSection section) {
        section.set("owner", ownerUuid.toString());
        section.set("owner-name", ownerName);
        section.set("item", item);
        section.set("locked", locked);
        section.set("created-at", createdAt);
        section.set("updated-at", updatedAt);
        ConfigurationSection locationSection = section.createSection("location");
        location.save(locationSection);
    }

    public static BindingRecord load(UUID id, ConfigurationSection section) {
        UUID ownerUuid = UUID.fromString(section.getString("owner"));
        String ownerName = section.getString("owner-name", "未知玩家");
        ItemStack item = section.getItemStack("item");
        boolean locked = section.getBoolean("locked", false);
        long createdAt = section.getLong("created-at", System.currentTimeMillis());
        long updatedAt = section.getLong("updated-at", createdAt);
        BindingLocation location = BindingLocation.load(section.getConfigurationSection("location"));
        return new BindingRecord(id, ownerUuid, ownerName, item, locked, location, createdAt, updatedAt);
    }
}
