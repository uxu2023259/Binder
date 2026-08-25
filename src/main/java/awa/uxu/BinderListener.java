package awa.uxu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BinderListener implements Listener {
    private final BinderPlugin plugin;
    private final BindingService service;
    private final BinderGui gui;
    private final Map<UUID, List<ItemStack>> pendingRespawnReturns = new HashMap<>();
    private final Map<String, List<PendingBoundItem>> pendingCrafterBindings = new HashMap<>();
    private static final Material CRAFTER_MATERIAL = Material.matchMaterial("CRAFTER");
    private final Map<UUID, PendingInventoryUpdate> pendingInventoryUpdates = new HashMap<>();
    private final Set<String> pendingBlockInventoryUpdates = new HashSet<>();

    public BinderListener(BinderPlugin plugin, BindingService service, BinderGui gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (gui.handleClick(event)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
        Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.ANVIL && service.hasBoundTemporaryInput(top) && service.isTemporaryResultSlot(top, event.getRawSlot())) {
            event.setCancelled(true);
            player.sendMessage(service.prefix() + ChatColor.RED + "绑定物不能从该界面的结果槽取出；请先取回原物品，避免复制或丢失。 ");
            service.playSound(player, "error");
            return;
        }
        if (!containsBound(current, cursor, hotbar)) {
            return;
        }
        service.alertOperatedByOther(player, "点击库存", current, cursor, hotbar);
        if (isLockedMoveForbidden(event, player, current, cursor, hotbar)) {
            event.setCancelled(true);
            player.sendMessage(service.prefix() + ChatColor.RED + "该绑定物已开启不可离包，不能离开绑定者背包。 ");
            return;
        }
        Set<Integer> rawSlots = new HashSet<>();
        if (event.getRawSlot() >= 0) {
            rawSlots.add(event.getRawSlot());
        }
        Set<Integer> playerSlots = new HashSet<>();
        if (event.getClickedInventory() instanceof PlayerInventory && event.getSlot() >= 0) {
            playerSlots.add(event.getSlot());
        }
        if (event.getHotbarButton() >= 0) {
            playerSlots.add(event.getHotbarButton());
        }
        scheduleInventoryUpdate(player, top, needsFullPlayerUpdate(event), true, rawSlots, playerSlots);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (containsBound(event.getCursor(), event.getCurrentItem())) {
            service.alertOperatedByOther(player, "创造模式操作", event.getCursor(), event.getCurrentItem());
            event.setCancelled(true);
            player.sendMessage(service.prefix() + ChatColor.RED + "创造模式下不能操作绑定物，避免复制风险。 ");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (service.isPluginGuiInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!service.containsBoundDeep(event.getOldCursor())) {
            return;
        }
        service.alertOperatedByOther(player, "拖动物品", event.getOldCursor());
        Inventory top = event.getView().getTopInventory();
        for (int rawSlot : event.getRawSlots()) {
            if (top.getType() != InventoryType.ANVIL && service.isTemporaryResultSlot(top, rawSlot)) {
                event.setCancelled(true);
                player.sendMessage(service.prefix() + ChatColor.RED + "绑定物不能拖入该界面的结果槽，避免复制或丢失。 ");
                service.playSound(player, "error");
                return;
            }
        }
        if (service.containsLockedDeep(event.getOldCursor())) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < top.getSize()) {
                    event.setCancelled(true);
                    player.sendMessage(service.prefix() + ChatColor.RED + "该绑定物已开启不可离包，不能放入容器或临时界面。 ");
                    return;
                }
            }
        }
        scheduleInventoryUpdate(player, top, false, true, new HashSet<>(event.getRawSlots()), Set.of());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (service.isPluginGuiInventory(top)) {
            return;
        }
        if (!containsBound(top.getContents()) && !service.containsBoundDeep(player.getItemOnCursor())) {
            return;
        }
        scheduleInventoryUpdate(player, top, true, true, Set.of(), Set.of());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (service.containsBoundDeep(event.getItem())) {
            if (service.containsLockedDeep(event.getItem())) {
                event.setCancelled(true);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (service.isSafeContainerInventory(event.getSource())) {
                    service.updateContainerLocations(event.getSource());
                }
                if (service.isSafeContainerInventory(event.getDestination())) {
                    service.updateContainerLocations(event.getDestination());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (service.containsBoundDeep(event.getItem().getItemStack())) {
            if (service.containsLockedDeep(event.getItem().getItemStack())) {
                event.setCancelled(true);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (service.isSafeContainerInventory(event.getInventory())) {
                    service.updateContainerLocations(event.getInventory());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (!service.containsBoundDeep(item)) {
            return;
        }
        service.alertOperatedByOther(event.getPlayer(), "丢弃到地面", item);
        if (service.containsLockedDeep(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该绑定物已开启不可离包，不能丢弃。 ");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> service.updateDroppedItem(event.getItemDrop()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (!service.containsBoundDeep(item)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (service.containsLockedBindingNotOwnedBy(item, player.getUniqueId())) {
            event.setCancelled(true);
            service.alertOperatedByOther(player, "尝试拾取", item);
            player.sendMessage(service.prefix() + ChatColor.RED + "该绑定物不可离开绑定者背包，你不能拾取。 ");
            return;
        }
        service.alertItemPickedUp(item, player);
        Bukkit.getScheduler().runTask(plugin, () -> service.updatePlayerLocations(player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (service.containsBoundDeep(event.getEntity().getItemStack())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                service.updateDroppedItem(event.getEntity());
                service.alertItemLanded(event.getEntity());
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (service.containsBoundDeep(event.getEntity().getItemStack())) {
            service.markLostDeep(event.getEntity().getItemStack(), "掉落物自然消失");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && service.containsBoundDeep(item.getItemStack())) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof ArmorStand armorStand && service.hasBoundArmorStandItem(armorStand)) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent damageByEntityEvent && damageByEntityEvent.getDamager() instanceof Player player) {
                player.sendMessage(service.prefix() + ChatColor.RED + "盔甲架上有绑定物，请先取下或召回后再破坏，避免丢失。 ");
                service.playSound(player, "error");
            }
            return;
        }
        if (event.getEntity() instanceof ItemFrame itemFrame && service.hasBoundItemFrameItem(itemFrame)) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent damageByEntityEvent && damageByEntityEvent.getDamager() instanceof Player player) {
                player.sendMessage(service.prefix() + ChatColor.RED + "物品展示框内有绑定物，请先召回后再破坏，避免丢失或复制。 ");
                service.playSound(player, "error");
            }
            return;
        }
        if (event.getEntity() instanceof ItemDisplay itemDisplay && service.hasBoundItemDisplayItem(itemDisplay)) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent damageByEntityEvent && damageByEntityEvent.getDamager() instanceof Player player) {
                player.sendMessage(service.prefix() + ChatColor.RED + "物品展示实体内有绑定物，请先召回后再移除，避免丢失或复制。 ");
                service.playSound(player, "error");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (service.containsBoundDeep(event.getEntity().getItemStack()) || service.containsBoundDeep(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (service.isBoundItem(event.getItem())) {
            service.alertOperatedByOther(event.getPlayer(), "尝试消耗", event.getItem());
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "绑定物不能被消耗。 ");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (service.containsLockedDeep(event.getItemInHand())) {
            service.alertOperatedByOther(event.getPlayer(), "尝试放置包含不可离包绑定物的物品", event.getItemInHand());
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该物品内包含不可离包绑定物，不能被放置。 ");
            return;
        }
        if (service.isBoundItem(event.getItemInHand())) {
            service.alertOperatedByOther(event.getPlayer(), "尝试放置", event.getItemInHand());
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "绑定物不能被放置。 ");
            return;
        }
        if (service.containsBoundDeep(event.getItemInHand())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                service.updatePlayerLocations(event.getPlayer());
                if (event.getBlockPlaced().getState() instanceof InventoryHolder holder) {
                    service.updateContainerLocations(holder.getInventory());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (isCrafter(event.getBlock()) && event.getBlock().getState() instanceof InventoryHolder holder) {
            String key = blockKey(event.getBlock());
            pendingCrafterBindings.remove(key);
            List<PendingBoundItem> pending = new ArrayList<>();
            for (ItemStack item : holder.getInventory().getContents()) {
                if (service.containsLockedDeep(item)) {
                    event.setCancelled(true);
                    return;
                }
                Set<UUID> ids = service.bindingIdsDeepView(item);
                if (!ids.isEmpty()) {
                    pending.add(new PendingBoundItem(item.clone(), ids));
                }
            }
            if (!pending.isEmpty()) {
                pendingCrafterBindings.put(key, pending);
                Bukkit.getScheduler().runTask(plugin, () -> pendingCrafterBindings.remove(key));
            }
        }
        if (service.containsLockedDeep(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (service.containsLockedDeep(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (service.containsLockedDeep(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (service.containsLockedDeep(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        for (ItemStack item : event.getContents().getContents()) {
            if (service.containsLockedDeep(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispenseTrack(BlockDispenseEvent event) {
        if (isCrafter(event.getBlock()) && event.getBlock().getState() instanceof InventoryHolder holder) {
            List<PendingBoundItem> pending = pendingCrafterBindings.remove(blockKey(event.getBlock()));
            if (pending != null) {
                Set<UUID> remaining = new HashSet<>();
                for (ItemStack item : holder.getInventory().getContents()) {
                    remaining.addAll(service.bindingIdsDeepView(item));
                }
                boolean consumed = false;
                for (PendingBoundItem item : pending) {
                    if (!containsAny(remaining, item.ids())) {
                        service.markLostDeep(item.item(), "合成器使用绑定物，等待重新扫描确认");
                        consumed = true;
                    }
                }
                if (consumed) {
                    scheduleBlockInventoryUpdate(event.getBlock());
                }
                return;
            }
            boolean foundInInventory = false;
            for (ItemStack item : holder.getInventory().getContents()) {
                if (service.containsBoundDeep(item)) {
                    foundInInventory = true;
                    break;
                }
            }
            if (foundInInventory) {
                scheduleBlockInventoryUpdate(event.getBlock());
                return;
            }
        }
        ItemStack item = event.getItem();
        if (!service.containsBoundDeep(item)) {
            return;
        }
        service.markLostDeep(item, "发射器或投掷器使用绑定物，等待重新扫描确认");
        scheduleBlockInventoryUpdate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceBurnTrack(FurnaceBurnEvent event) {
        ItemStack fuel = event.getFuel();
        if (event.willConsumeFuel() && service.containsBoundDeep(fuel)) {
            service.markLostDeep(fuel, "熔炉消耗绑定燃料");
            scheduleBlockInventoryUpdate(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceSmeltTrack(FurnaceSmeltEvent event) {
        ItemStack source = event.getSource();
        if (service.containsBoundDeep(source)) {
            service.markLostDeep(source, "熔炉烧炼绑定物");
            scheduleBlockInventoryUpdate(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingFuelTrack(BrewingStandFuelEvent event) {
        ItemStack fuel = event.getFuel();
        if (event.isConsuming() && service.containsBoundDeep(fuel)) {
            service.markLostDeep(fuel, "酿造台消耗绑定燃料");
            scheduleBlockInventoryUpdate(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewTrack(BrewEvent event) {
        if (!containsBound(event.getContents().getContents())) {
            return;
        }
        for (ItemStack item : event.getContents().getContents()) {
            if (service.containsBoundDeep(item)) {
                service.markLostDeep(item, "酿造台转化绑定物");
            }
        }
        scheduleBlockInventoryUpdate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!service.isBoundItem(item)) {
            return;
        }
        if (service.isUnsafeUse(item.getType())) {
            service.alertOperatedByOther(event.getPlayer(), "尝试使用", item);
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该绑定物不能这样使用，避免被消耗或失踪。 ");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (!(event.getRightClicked() instanceof ItemFrame itemFrame)) {
            if (service.isBoundItem(item)
                    && !(event.getRightClicked() instanceof ArmorStand)
                    && !(event.getRightClicked() instanceof InventoryHolder)) {
                service.alertOperatedByOther(event.getPlayer(), "尝试用于实体", item);
                event.setCancelled(true);
                event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "绑定物不能直接用于该实体，避免被消耗或失踪。 ");
                service.playSound(event.getPlayer(), "error");
            }
            if (service.containsLockedDeep(item) && event.getRightClicked() instanceof InventoryHolder) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该物品内包含不可离包绑定物，不能放入实体容器。 ");
                service.playSound(event.getPlayer(), "error");
                return;
            }
            if (service.containsBoundDeep(item) && event.getRightClicked() instanceof InventoryHolder holder) {
                service.alertOperatedByOther(event.getPlayer(), "放入实体容器", item);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    service.updatePlayerLocations(event.getPlayer());
                    service.updateContainerLocations(holder.getInventory());
                });
            }
            return;
        }
        if (!service.containsBoundDeep(item) && !service.hasBoundItemFrameItem(itemFrame)) {
            return;
        }
        ItemStack frameItem = itemFrame.getItem();
        service.alertOperatedByOther(event.getPlayer(), "操作物品展示框", item, frameItem);
        if (service.containsLockedBindingNotOwnedBy(frameItem, event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该绑定物不可离开绑定者背包，你不能从物品展示框取下或操作。 ");
            service.playSound(event.getPlayer(), "error");
            return;
        }
        if (service.containsLockedDeep(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该绑定物已开启不可离包，不能放入物品展示框。 ");
            service.playSound(event.getPlayer(), "error");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            service.updatePlayerLocations(event.getPlayer());
            service.updateItemFrameLocation(itemFrame);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame itemFrame) || !service.hasBoundItemFrameItem(itemFrame)) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof HangingBreakByEntityEvent byEntityEvent && byEntityEvent.getRemover() instanceof Player player) {
            player.sendMessage(service.prefix() + ChatColor.RED + "物品展示框内有绑定物，请先召回后再破坏，避免丢失或复制。 ");
            service.playSound(player, "error");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        ItemStack playerItem = event.getPlayerItem();
        ItemStack standItem = event.getArmorStandItem();
        if (!service.containsBoundDeep(playerItem) && !service.containsBoundDeep(standItem)) {
            return;
        }
        service.alertOperatedByOther(event.getPlayer(), "操作盔甲架", playerItem, standItem);
        if (service.containsLockedDeep(playerItem)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该绑定物已开启不可离包，不能放到盔甲架上。 ");
            service.playSound(event.getPlayer(), "error");
            return;
        }
        if (service.containsLockedBindingNotOwnedBy(standItem, event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "该绑定物不可离开绑定者背包，你不能从盔甲架取下。 ");
            service.playSound(event.getPlayer(), "error");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            service.updatePlayerLocations(event.getPlayer());
            service.updateArmorStandLocations(event.getRightClicked());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (containsBound(event.getInventory().getMatrix())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                service.alertOperatedByOther(player, "尝试作为合成材料", event.getInventory().getMatrix());
                player.sendMessage(service.prefix() + ChatColor.RED + "绑定物不能作为合成材料。 ");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsBound(event.getInventory().getMatrix())) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getItem(0);
        ItemStack second = event.getInventory().getItem(1);
        ItemStack result = event.getResult();
        if (!service.isBoundItem(first) && !service.isBoundItem(second)) {
            return;
        }
        if (!service.isBoundItem(first) || service.isBoundItem(second) || service.isEmpty(result)) {
            event.setResult(new ItemStack(Material.AIR));
            return;
        }
        Optional<UUID> id = service.getBindingId(first);
        if (id.isEmpty()) {
            event.setResult(new ItemStack(Material.AIR));
            return;
        }
        service.getStore().find(id.get()).ifPresentOrElse(
                record -> event.setResult(service.applyBinding(result, record)),
                () -> event.setResult(new ItemStack(Material.AIR))
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDurability(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (!service.isBoundItem(item)) {
            return;
        }
        if (item.getItemMeta() instanceof Damageable damageable) {
            int max = item.getType().getMaxDurability();
            if (max > 0 && damageable.getDamage() + event.getDamage() >= max) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(service.prefix() + ChatColor.RED + "绑定物耐久不足，已阻止其损坏消失。 ");
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> service.updatePlayerLocations(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> service.updatePlayerLocations(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        service.dropBoundItemsFromContainer(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            service.dropBoundItemsFromContainer(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            service.dropBoundItemsFromContainer(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> service.updatePlayerLocations(event.getPlayer()), 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        service.updatePlayerLocations(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        Player player = event.getEntity();
        List<ItemStack> returns = new ArrayList<>();
        Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (service.containsLockedDeep(item)) {
                iterator.remove();
                returns.add(item.clone());
                service.markLostDeep(item, "玩家死亡时暂存不可离包物品");
            }
        }
        if (!returns.isEmpty()) {
            pendingRespawnReturns.computeIfAbsent(player.getUniqueId(), key -> new ArrayList<>()).addAll(returns);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        List<ItemStack> returns = pendingRespawnReturns.remove(event.getPlayer().getUniqueId());
        if (returns == null || returns.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (ItemStack item : returns) {
                service.returnItemToOwnerInventory(event.getPlayer(), item);
            }
        }, 5L);
    }

    private boolean isLockedMoveForbidden(InventoryClickEvent event, Player player, ItemStack current, ItemStack cursor, ItemStack hotbar) {
        if (service.containsLockedBindingNotOwnedBy(current, player.getUniqueId())) {
            return true;
        }
        if (service.containsLockedBindingNotOwnedBy(cursor, player.getUniqueId())) {
            return true;
        }
        if (service.containsLockedBindingNotOwnedBy(hotbar, player.getUniqueId())) {
            return true;
        }
        Inventory clicked = event.getClickedInventory();
        Inventory top = event.getView().getTopInventory();
        if (service.containsLockedDeep(current)) {
            if (isDropAction(event.getAction())) {
                return true;
            }
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY && clicked instanceof PlayerInventory) {
                return true;
            }
        }
        if (service.containsLockedDeep(cursor)) {
            if (isDropAction(event.getAction())) {
                return true;
            }
            if (event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize()) {
                return true;
            }
        }
        return service.containsLockedDeep(hotbar) && event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();
    }

    private boolean isDropAction(org.bukkit.event.inventory.InventoryAction action) {
        return action == org.bukkit.event.inventory.InventoryAction.DROP_ALL_CURSOR
                || action == org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR
                || action == org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT
                || action == org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT;
    }

    private boolean needsFullPlayerUpdate(InventoryClickEvent event) {
        return event.isShiftClick()
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR
                || event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK;
    }

    private void scheduleInventoryUpdate(Player player, Inventory top, boolean fullPlayer, boolean includeCursor, Set<Integer> rawSlots, Set<Integer> playerSlots) {
        UUID playerId = player.getUniqueId();
        PendingInventoryUpdate pending = pendingInventoryUpdates.get(playerId);
        if (pending != null) {
            pending.merge(top, fullPlayer, includeCursor, rawSlots, playerSlots);
            return;
        }
        pending = new PendingInventoryUpdate(top, fullPlayer, includeCursor, rawSlots, playerSlots);
        pendingInventoryUpdates.put(playerId, pending);
        Bukkit.getScheduler().runTask(plugin, () -> {
            PendingInventoryUpdate pendingUpdate = pendingInventoryUpdates.remove(playerId);
            if (!player.isOnline()) {
                return;
            }
            if (pendingUpdate == null) {
                return;
            }
            service.updateInventoryInteractionLocations(
                    player,
                    pendingUpdate.top,
                    pendingUpdate.rawSlots,
                    pendingUpdate.playerSlots,
                    pendingUpdate.includeCursor,
                    pendingUpdate.fullPlayer
            );
        });
    }

    private void scheduleBlockInventoryUpdate(Block block) {
        String key = blockKey(block);
        if (!pendingBlockInventoryUpdates.add(key)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (block.getState() instanceof InventoryHolder holder) {
                    service.updateContainerLocations(holder.getInventory());
                }
            } finally {
                pendingBlockInventoryUpdates.remove(key);
            }
        });
    }

    private boolean containsBound(ItemStack... items) {
        for (ItemStack item : items) {
            if (service.containsBoundDeep(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCrafter(Block block) {
        return CRAFTER_MATERIAL != null && block.getType() == CRAFTER_MATERIAL;
    }

    private boolean containsAny(Set<UUID> remaining, Set<UUID> ids) {
        for (UUID id : ids) {
            if (remaining.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static final class PendingInventoryUpdate {
        private Inventory top;
        private final Set<Integer> rawSlots = new HashSet<>();
        private final Set<Integer> playerSlots = new HashSet<>();
        private boolean fullPlayer;
        private boolean includeCursor;

        private PendingInventoryUpdate(Inventory top, boolean fullPlayer, boolean includeCursor, Set<Integer> rawSlots, Set<Integer> playerSlots) {
            merge(top, fullPlayer, includeCursor, rawSlots, playerSlots);
        }

        private void merge(Inventory top, boolean fullPlayer, boolean includeCursor, Set<Integer> rawSlots, Set<Integer> playerSlots) {
            if (top != null) {
                this.top = top;
            }
            this.fullPlayer |= fullPlayer;
            this.includeCursor |= includeCursor;
            this.rawSlots.addAll(rawSlots);
            this.playerSlots.addAll(playerSlots);
        }
    }

    private record PendingBoundItem(ItemStack item, Set<UUID> ids) {
    }
}
