package awa.uxu;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

public final class BindingLocation {
    public enum Type {
        PLAYER,
        TEMPORARY,
        CONTAINER,
        DROPPED,
        LOST
    }

    private final Type type;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final UUID entityUuid;
    private final UUID holderUuid;
    private final String holderName;

    private BindingLocation(Type type, String world, int x, int y, int z, UUID entityUuid, UUID holderUuid, String holderName) {
        this.type = type;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.entityUuid = entityUuid;
        this.holderUuid = holderUuid;
        this.holderName = holderName;
    }

    public static BindingLocation player(Player player) {
        return new BindingLocation(Type.PLAYER, null, 0, 0, 0, null, player.getUniqueId(), player.getName());
    }

    public static BindingLocation holder(UUID uuid, String name) {
        return new BindingLocation(Type.PLAYER, null, 0, 0, 0, null, uuid, name);
    }

    public static BindingLocation temporary(Player player, String description) {
        return new BindingLocation(Type.TEMPORARY, null, 0, 0, 0, null, player.getUniqueId(), description);
    }

    public static BindingLocation container(Location location) {
        if (location == null || location.getWorld() == null) {
            return lost();
        }
        return new BindingLocation(Type.CONTAINER, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), null, null, null);
    }

    public static BindingLocation entityContainer(Entity entity) {
        if (entity == null || entity.getWorld() == null) {
            return lost();
        }
        Location location = entity.getLocation();
        return new BindingLocation(Type.CONTAINER, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), entity.getUniqueId(), null, entityTypeName(entity));
    }

    public static BindingLocation dropped(Item item) {
        if (item == null || item.getWorld() == null) {
            return lost();
        }
        Location location = item.getLocation();
        return new BindingLocation(Type.DROPPED, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), item.getUniqueId(), null, null);
    }

    public static BindingLocation lost() {
        return new BindingLocation(Type.LOST, null, 0, 0, 0, null, null, null);
    }

    public Type getType() {
        return type;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public UUID getEntityUuid() {
        return entityUuid;
    }

    public UUID getHolderUuid() {
        return holderUuid;
    }

    public String getHolderName() {
        return holderName;
    }

    public Location toBlockLocation() {
        if (world == null) {
            return null;
        }
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return null;
        }
        return new Location(bukkitWorld, x, y, z);
    }

    public String describe() {
        return switch (type) {
            case PLAYER -> holderName == null ? "玩家背包" : "玩家背包：" + holderName;
            case TEMPORARY -> holderName == null ? "玩家临时界面" : holderName;
            case CONTAINER -> world == null ? "容器位置未知" : (entityUuid == null ? "容器：" : "实体容器：" + (holderName == null ? "" : holderName + " ")) + world + " " + x + ", " + y + ", " + z;
            case DROPPED -> world == null ? "掉落物位置未知" : "掉落物：" + world + " " + x + ", " + y + ", " + z;
            case LOST -> "已丢失";
        };
    }

    public void save(ConfigurationSection section) {
        section.set("type", type.name());
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("entity", entityUuid == null ? null : entityUuid.toString());
        section.set("holder", holderUuid == null ? null : holderUuid.toString());
        section.set("holder-name", holderName);
    }

    public static BindingLocation load(ConfigurationSection section) {
        if (section == null) {
            return lost();
        }
        Type type;
        try {
            type = Type.valueOf(section.getString("type", "LOST"));
        } catch (IllegalArgumentException ex) {
            type = Type.LOST;
        }
        UUID entity = parseUuid(section.getString("entity"));
        UUID holder = parseUuid(section.getString("holder"));
        return new BindingLocation(
                type,
                section.getString("world"),
                section.getInt("x"),
                section.getInt("y"),
                section.getInt("z"),
                entity,
                holder,
                section.getString("holder-name")
        );
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof BindingLocation other)) {
            return false;
        }
        return x == other.x
                && y == other.y
                && z == other.z
                && type == other.type
                && Objects.equals(world, other.world)
                && Objects.equals(entityUuid, other.entityUuid)
                && Objects.equals(holderUuid, other.holderUuid)
                && Objects.equals(holderName, other.holderName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, world, x, y, z, entityUuid, holderUuid, holderName);
    }

    private static String entityTypeName(Entity entity) {
        String entityType = entity.getType().name();
        if ("CHEST_MINECART".equals(entityType) || "MINECART_CHEST".equals(entityType)) {
            return "箱子矿车";
        }
        if ("HOPPER_MINECART".equals(entityType) || "MINECART_HOPPER".equals(entityType)) {
            return "漏斗矿车";
        }
        return switch (entity.getType()) {
            case ARMOR_STAND -> "盔甲架";
            case ITEM_FRAME -> "物品展示框";
            case GLOW_ITEM_FRAME -> "荧光物品展示框";
            case ITEM_DISPLAY -> "物品展示实体";
            case MINECART -> "矿车";
            case HORSE -> "马";
            case DONKEY -> "驴";
            case MULE -> "骡";
            case LLAMA -> "羊驼";
            default -> "实体容器";
        };
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
