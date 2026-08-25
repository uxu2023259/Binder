package awa.uxu;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class FoundItem {
    public enum Source {
        PLAYER,
        ANVIL,
        TEMPORARY,
        ARMOR_STAND,
        ITEM_FRAME,
        ITEM_DISPLAY,
        CURSOR,
        NESTED,
        CONTAINER,
        DROPPED
    }

    private enum NestedStorage {
        BLOCK_INVENTORY,
        BUNDLE
    }

    private final Source source;
    private final Player player;
    private final Inventory inventory;
    private final int slot;
    private final Item entity;
    private final Entity containerEntity;
    private final Location containerLocation;
    private final String temporaryDescription;
    private final ArmorStand armorStand;
    private final EquipmentSlot equipmentSlot;
    private final FoundItem parent;
    private final NestedStorage nestedStorage;

    private FoundItem(Source source, Player player, Inventory inventory, int slot, Item entity, Entity containerEntity, Location containerLocation, String temporaryDescription, ArmorStand armorStand, EquipmentSlot equipmentSlot, FoundItem parent, NestedStorage nestedStorage) {
        this.source = source;
        this.player = player;
        this.inventory = inventory;
        this.slot = slot;
        this.entity = entity;
        this.containerEntity = containerEntity;
        this.containerLocation = containerLocation;
        this.temporaryDescription = temporaryDescription;
        this.armorStand = armorStand;
        this.equipmentSlot = equipmentSlot;
        this.parent = parent;
        this.nestedStorage = nestedStorage;
    }

    public static FoundItem player(Player player, int slot) {
        return new FoundItem(Source.PLAYER, player, player.getInventory(), slot, null, null, null, null, null, null, null, null);
    }

    public static FoundItem anvil(Player player, Inventory inventory, int slot) {
        return new FoundItem(Source.ANVIL, player, inventory, slot, null, null, null, null, null, null, null, null);
    }

    public static FoundItem temporary(Player player, Inventory inventory, int slot, String description) {
        return new FoundItem(Source.TEMPORARY, player, inventory, slot, null, null, null, description, null, null, null, null);
    }

    public static FoundItem armorStand(ArmorStand armorStand, EquipmentSlot slot) {
        return new FoundItem(Source.ARMOR_STAND, null, null, -1, null, armorStand, armorStand.getLocation(), null, armorStand, slot, null, null);
    }

    public static FoundItem itemFrame(ItemFrame itemFrame) {
        return new FoundItem(Source.ITEM_FRAME, null, null, -1, null, itemFrame, itemFrame.getLocation(), null, null, null, null, null);
    }

    public static FoundItem itemDisplay(ItemDisplay itemDisplay) {
        return new FoundItem(Source.ITEM_DISPLAY, null, null, -1, null, itemDisplay, itemDisplay.getLocation(), null, null, null, null, null);
    }

    public static FoundItem cursor(Player player) {
        return new FoundItem(Source.CURSOR, player, null, -1, null, null, null, null, null, null, null, null);
    }

    public static FoundItem nestedBlockInventory(FoundItem parent, int slot) {
        return new FoundItem(Source.NESTED, null, null, slot, null, null, null, null, null, null, parent, NestedStorage.BLOCK_INVENTORY);
    }

    public static FoundItem nestedBundle(FoundItem parent, int slot) {
        return new FoundItem(Source.NESTED, null, null, slot, null, null, null, null, null, null, parent, NestedStorage.BUNDLE);
    }

    public static FoundItem container(Inventory inventory, int slot, Location location) {
        return new FoundItem(Source.CONTAINER, null, inventory, slot, null, null, location, null, null, null, null, null);
    }

    public static FoundItem entityContainer(Inventory inventory, int slot, Entity entity) {
        return new FoundItem(Source.CONTAINER, null, inventory, slot, null, entity, entity.getLocation(), null, null, null, null, null);
    }

    public static FoundItem dropped(Item entity) {
        return new FoundItem(Source.DROPPED, null, null, -1, entity, null, null, null, null, null, null, null);
    }

    public Source getSource() {
        return source;
    }

    public Player getPlayer() {
        if (source == Source.NESTED) {
            return parent.getPlayer();
        }
        return player;
    }

    public Item getEntity() {
        if (source == Source.NESTED) {
            return parent.getEntity();
        }
        return entity;
    }

    public Location getContainerLocation() {
        if (source == Source.NESTED) {
            return parent.getContainerLocation();
        }
        if (containerEntity != null) {
            return containerEntity.getLocation();
        }
        return containerLocation;
    }

    public Entity getContainerEntity() {
        if (source == Source.NESTED) {
            return parent.getContainerEntity();
        }
        if (source == Source.ARMOR_STAND) {
            return armorStand;
        }
        return containerEntity;
    }

    public ItemStack getItemStack() {
        if (source == Source.DROPPED) {
            return entity.getItemStack();
        }
        if (source == Source.CURSOR) {
            return player.getItemOnCursor();
        }
        if (source == Source.ARMOR_STAND) {
            EntityEquipment equipment = armorStand.getEquipment();
            return equipment == null ? null : equipment.getItem(equipmentSlot);
        }
        if (source == Source.ITEM_FRAME) {
            return ((ItemFrame) containerEntity).getItem();
        }
        if (source == Source.ITEM_DISPLAY) {
            return ((ItemDisplay) containerEntity).getItemStack();
        }
        if (source == Source.NESTED) {
            return getNestedItem();
        }
        return inventory.getItem(slot);
    }

    public void setItemStack(ItemStack itemStack) {
        if (source == Source.DROPPED) {
            entity.setItemStack(itemStack);
            return;
        }
        if (source == Source.CURSOR) {
            player.setItemOnCursor(itemStack);
            return;
        }
        if (source == Source.ARMOR_STAND) {
            EntityEquipment equipment = armorStand.getEquipment();
            if (equipment != null) {
                equipment.setItem(equipmentSlot, itemStack);
            }
            return;
        }
        if (source == Source.ITEM_FRAME) {
            ((ItemFrame) containerEntity).setItem(itemStack, false);
            return;
        }
        if (source == Source.ITEM_DISPLAY) {
            ((ItemDisplay) containerEntity).setItemStack(itemStack);
            return;
        }
        if (source == Source.NESTED) {
            setNestedItem(itemStack);
            return;
        }
        inventory.setItem(slot, itemStack);
    }

    public boolean remove() {
        if (source == Source.DROPPED) {
            if (entity.isDead() || !entity.isValid()) {
                return false;
            }
            entity.remove();
            return true;
        }
        if (source == Source.CURSOR) {
            ItemStack current = player.getItemOnCursor();
            if (current == null || current.getType().isAir()) {
                return false;
            }
            player.setItemOnCursor(null);
            return true;
        }
        if (source == Source.ARMOR_STAND) {
            EntityEquipment equipment = armorStand.getEquipment();
            if (equipment == null) {
                return false;
            }
            ItemStack current = equipment.getItem(equipmentSlot);
            if (current == null || current.getType().isAir()) {
                return false;
            }
            equipment.setItem(equipmentSlot, null);
            return true;
        }
        if (source == Source.ITEM_FRAME) {
            ItemFrame itemFrame = (ItemFrame) containerEntity;
            ItemStack current = itemFrame.getItem();
            if (current == null || current.getType().isAir()) {
                return false;
            }
            itemFrame.setItem(new ItemStack(Material.AIR), false);
            return true;
        }
        if (source == Source.ITEM_DISPLAY) {
            ItemDisplay itemDisplay = (ItemDisplay) containerEntity;
            ItemStack current = itemDisplay.getItemStack();
            if (current == null || current.getType().isAir()) {
                return false;
            }
            itemDisplay.setItemStack(new ItemStack(Material.AIR));
            return true;
        }
        if (source == Source.NESTED) {
            ItemStack current = getNestedItem();
            if (current == null || current.getType().isAir()) {
                return false;
            }
            return setNestedItem(null) && (getNestedItem() == null || getNestedItem().getType().isAir());
        }
        ItemStack current = inventory.getItem(slot);
        if (current == null || current.getType().isAir()) {
            return false;
        }
        inventory.setItem(slot, null);
        return true;
    }

    public BindingLocation toBindingLocation() {
        return switch (source) {
            case PLAYER -> BindingLocation.player(player);
            case ANVIL -> BindingLocation.temporary(player, "玩家铁砧界面：" + player.getName());
            case TEMPORARY -> BindingLocation.temporary(player, temporaryDescription == null ? "玩家临时界面：" + player.getName() : temporaryDescription);
            case ARMOR_STAND -> BindingLocation.entityContainer(armorStand);
            case ITEM_FRAME -> BindingLocation.entityContainer(containerEntity);
            case ITEM_DISPLAY -> BindingLocation.entityContainer(containerEntity);
            case CURSOR -> BindingLocation.temporary(player, "玩家鼠标：" + player.getName());
            case NESTED -> parent.toBindingLocation();
            case CONTAINER -> containerEntity == null ? BindingLocation.container(containerLocation) : BindingLocation.entityContainer(containerEntity);
            case DROPPED -> BindingLocation.dropped(entity);
        };
    }

    public String identityKey() {
        return switch (source) {
            case PLAYER -> "玩家背包:" + player.getUniqueId() + ":" + slot;
            case ANVIL, TEMPORARY -> "临时界面:" + player.getUniqueId() + ":" + System.identityHashCode(inventory) + ":" + slot;
            case ARMOR_STAND -> "盔甲架:" + armorStand.getUniqueId() + ":" + equipmentSlot.name();
            case ITEM_FRAME -> "物品展示框:" + containerEntity.getUniqueId();
            case ITEM_DISPLAY -> "物品展示实体:" + containerEntity.getUniqueId();
            case CURSOR -> "玩家鼠标:" + player.getUniqueId();
            case NESTED -> parent.identityKey() + ":收纳:" + nestedStorage.name() + ":" + slot;
            case CONTAINER -> {
                if (containerEntity != null) {
                    yield "实体容器:" + containerEntity.getUniqueId() + ":" + slot;
                }
                Location location = getContainerLocation();
                if (location != null && location.getWorld() != null) {
                    yield "方块容器:" + location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ() + ":" + slot;
                }
                yield "未知容器:" + System.identityHashCode(inventory) + ":" + slot;
            }
            case DROPPED -> "掉落物:" + entity.getUniqueId();
        };
    }

    public String describe() {
        return switch (source) {
            case PLAYER -> "玩家背包：" + player.getName();
            case ANVIL -> "玩家铁砧界面：" + player.getName();
            case TEMPORARY -> temporaryDescription == null ? "玩家临时界面：" + player.getName() : temporaryDescription;
            case ARMOR_STAND -> "盔甲架：" + formatLocation(armorStand == null ? null : armorStand.getLocation()) + "，槽位：" + armorSlotName(equipmentSlot);
            case ITEM_FRAME -> "物品展示框：" + formatLocation(containerEntity == null ? null : containerEntity.getLocation());
            case ITEM_DISPLAY -> "物品展示实体：" + formatLocation(containerEntity == null ? null : containerEntity.getLocation());
            case CURSOR -> "玩家鼠标：" + player.getName();
            case NESTED -> "物品收纳容器内：" + parent.describe() + "，槽位：" + (slot + 1);
            case CONTAINER -> containerEntity == null
                    ? "容器：" + formatLocation(containerLocation)
                    : "实体容器：" + containerEntity.getType().name() + " " + formatLocation(containerEntity.getLocation());
            case DROPPED -> "掉落物：" + formatLocation(entity == null ? null : entity.getLocation());
        };
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "未知位置";
        }
        return location.getWorld().getName() + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private String armorSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头部";
            case CHEST -> "胸甲";
            case LEGS -> "护腿";
            case FEET -> "靴子";
            case HAND -> "主手";
            case OFF_HAND -> "副手";
            default -> "未知";
        };
    }

    private ItemStack getNestedItem() {
        ItemStack container = parent.getItemStack();
        if (container == null || container.getType().isAir() || !container.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = container.getItemMeta();
        if (nestedStorage == NestedStorage.BLOCK_INVENTORY && meta instanceof BlockStateMeta blockStateMeta) {
            BlockState blockState = blockStateMeta.getBlockState();
            if (blockState instanceof InventoryHolder holder && slot >= 0 && slot < holder.getInventory().getSize()) {
                return holder.getInventory().getItem(slot);
            }
        }
        if (nestedStorage == NestedStorage.BUNDLE && meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = bundleMeta.getItems();
            if (slot >= 0 && slot < items.size()) {
                return items.get(slot);
            }
        }
        return null;
    }

    private boolean setNestedItem(ItemStack itemStack) {
        ItemStack container = parent.getItemStack();
        if (container == null || container.getType().isAir() || !container.hasItemMeta()) {
            return false;
        }
        ItemStack updatedContainer = container.clone();
        ItemMeta meta = updatedContainer.getItemMeta();
        if (nestedStorage == NestedStorage.BLOCK_INVENTORY && meta instanceof BlockStateMeta blockStateMeta) {
            BlockState blockState = blockStateMeta.getBlockState();
            if (blockState instanceof InventoryHolder holder && slot >= 0 && slot < holder.getInventory().getSize()) {
                holder.getInventory().setItem(slot, itemStack);
                blockStateMeta.setBlockState(blockState);
                updatedContainer.setItemMeta(blockStateMeta);
                parent.setItemStack(updatedContainer);
                return true;
            }
            return false;
        }
        if (nestedStorage == NestedStorage.BUNDLE && meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = new ArrayList<>(bundleMeta.getItems());
            boolean empty = itemStack == null || itemStack.getType().isAir();
            if (slot >= 0 && slot < items.size()) {
                if (empty) {
                    items.remove(slot);
                } else {
                    items.set(slot, itemStack);
                }
            } else if (!empty && slot == items.size()) {
                items.add(itemStack);
            }
            bundleMeta.setItems(items);
            updatedContainer.setItemMeta(bundleMeta);
            parent.setItemStack(updatedContainer);
            return true;
        }
        return false;
    }
}
