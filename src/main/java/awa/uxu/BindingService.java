package awa.uxu;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BindingService {
    private static final int MAX_NESTED_DEPTH = 4;
    private static final int[] NO_TEMPORARY_INPUT_SLOTS = new int[0];
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    public enum ConfirmType {
        OTHER_PLAYER,
        LOST
    }

    private final BinderPlugin plugin;
    private final BindingStore store;
    private final org.bukkit.NamespacedKey idKey;
    private final org.bukkit.NamespacedKey ownerKey;
    private final org.bukkit.NamespacedKey lockedKey;
    private final BinderMessages messages;
    private Economy economy;
    private BinderGui gui;
    private CoreProtectHook coreProtectHook;
    private final Map<UUID, Long> recallCooldowns = new HashMap<>();
    private final Map<String, Long> alertCooldowns = new HashMap<>();
    private final Map<UUID, Boolean> alertPreferences = new HashMap<>();
    private final Map<String, Set<Material>> materialCache = new HashMap<>();
    private final Set<String> pendingCoreProtectCandidateVerifications = ConcurrentHashMap.newKeySet();
    private long lastEventBackupAt;
    private boolean eventBackupQueued;
    private String queuedEventBackupReason;
    private int automaticScanCursor;
    private int fullScanCursor;
    private BukkitTask fullScanTask;

    public BindingService(BinderPlugin plugin, BindingStore store, BinderMessages messages) {
        this.plugin = plugin;
        this.store = store;
        this.messages = messages;
        this.idKey = new org.bukkit.NamespacedKey(plugin, "binding_id");
        this.ownerKey = new org.bukkit.NamespacedKey(plugin, "binding_owner");
        this.lockedKey = new org.bukkit.NamespacedKey(plugin, "binding_locked");
        loadAlertPreferences();
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    public void setGui(BinderGui gui) {
        this.gui = gui;
    }

    public void setCoreProtectHook(CoreProtectHook coreProtectHook) {
        this.coreProtectHook = coreProtectHook;
    }

    public void clearMaterialCache() {
        materialCache.clear();
    }

    public BindingStore getStore() {
        return store;
    }

    public String prefix() {
        return messages.prefix();
    }

    public String message(String path, String fallback) {
        return messages.text(path, fallback);
    }

    public List<String> messageList(String path, List<String> fallback) {
        return messages.list(path, fallback);
    }

    public boolean bindMainHand(Player player) {
        int max = maxPerPlayer();
        if (store.byOwner(player.getUniqueId()).size() >= max) {
            player.sendMessage(prefix() + ChatColor.RED + "绑定数量已达上限：" + max + "。 ");
            playSound(player, "error");
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isEmpty(hand)) {
            player.sendMessage(prefix() + ChatColor.RED + "请先把要绑定的物品拿在主手。 ");
            playSound(player, "error");
            return false;
        }
        if (hand.getAmount() != 1) {
            player.sendMessage(prefix() + ChatColor.RED + "绑定物品数量必须为 1，避免复制风险。 ");
            playSound(player, "error");
            return false;
        }
        if (!canBindMaterial(player, hand.getType())) {
            return false;
        }
        if (getBindingId(hand).isPresent()) {
            player.sendMessage(prefix() + ChatColor.RED + "该物品已绑定。 ");
            playSound(player, "error");
            return false;
        }
        if (containsBoundDeep(hand)) {
            player.sendMessage(prefix() + ChatColor.RED + "该物品内部已有绑定物，不能再绑定外层容器。请先取出后再试。 ");
            playSound(player, "error");
            return false;
        }
        double cost = bindCost();
        if (!withdraw(player, cost)) {
            return false;
        }
        boolean locked = plugin.getConfig().getBoolean("binding.default-locked", false);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        BindingRecord record = new BindingRecord(id, player.getUniqueId(), player.getName(), hand.clone(), locked, BindingLocation.player(player), now, now);
        ItemStack bound = applyBinding(hand, record);
        record.setItem(bound.clone());
        player.getInventory().setItemInMainHand(bound);
        store.put(record);
        requestEventBackup("新增绑定记录");
        player.sendMessage(prefix() + ChatColor.GREEN + "绑定成功，编号：" + store.ownerIndex(record) + "，绑定者：" + player.getName() + "。 ");
        if (cost > 0.0D) {
            player.sendMessage(prefix() + ChatColor.YELLOW + "已扣除绑定费用：" + formatMoney(cost) + "。 ");
        }
        playSound(player, "success");
        return true;
    }

    public void requestRecall(Player player, BindingRecord record, boolean admin) {
        if (admin && !requireAdmin(player)) {
            return;
        }
        if (!admin && !record.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(prefix() + ChatColor.RED + "只能召回自己的绑定物。 ");
            playSound(player, "error");
            return;
        }
        if (!admin && !checkRecallCooldown(player)) {
            return;
        }
        FoundItem found = locateOne(record, true, true);
        if (found == null) {
            if (hasKnownPlayerLikeLocation(record)) {
                player.sendMessage(prefix() + ChatColor.RED + "物品可能正在背包或临时界面同步中，暂不能判定丢失。请稍后再试。 ");
                playSound(player, "error");
                return;
            }
            if (!canCreateLostReplacement(record)) {
                player.sendMessage(prefix() + ChatColor.RED + "暂不能丢失召回：无法确认原物品已消失。请先刷新位置或检查容器。 ");
                playSound(player, "error");
                return;
            }
            record.setLocation(BindingLocation.lost());
            store.markDirty(record);
            store.saveDirty();
            if (admin) {
                recallLostForAdmin(player, record);
                return;
            }
            gui.openConfirm(player, record, ConfirmType.LOST);
            return;
        }
        if (!admin && isHeldByOtherPlayer(found, player.getUniqueId())) {
            gui.openConfirm(player, record, ConfirmType.OTHER_PLAYER);
            return;
        }
        Player destination = admin ? Bukkit.getPlayer(record.getOwnerUuid()) : player;
        if (destination == null) {
            player.sendMessage(prefix() + ChatColor.RED + "绑定者不在线，无法发还物品。 ");
            playSound(player, "error");
            return;
        }
        recallFound(destination, record, found, admin ? player : null);
    }

    public void confirmRecall(Player player, UUID recordId, ConfirmType type) {
        Optional<BindingRecord> optional = store.find(recordId);
        if (optional.isEmpty()) {
            player.sendMessage(prefix() + ChatColor.RED + "该绑定记录不存在。 ");
            playSound(player, "error");
            return;
        }
        BindingRecord record = optional.get();
        if (!record.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(prefix() + ChatColor.RED + "只能确认召回自己的绑定物。 ");
            playSound(player, "error");
            return;
        }
        if (!checkRecallCooldown(player)) {
            return;
        }
        if (type == ConfirmType.OTHER_PLAYER) {
            FoundItem found = locateOne(record, true, true);
            if (found == null) {
                if (hasKnownPlayerLikeLocation(record)) {
                    player.sendMessage(prefix() + ChatColor.RED + "物品可能正在背包或临时界面同步中，暂不能判定丢失。请稍后再试。 ");
                    playSound(player, "error");
                    return;
                }
                if (!canCreateLostReplacement(record)) {
                    player.sendMessage(prefix() + ChatColor.RED + "暂不能丢失召回：无法确认原物品已消失。请先刷新位置或检查容器。 ");
                    playSound(player, "error");
                    return;
                }
                record.setLocation(BindingLocation.lost());
                store.markDirty(record);
                store.saveDirty();
                gui.openConfirm(player, record, ConfirmType.LOST);
                return;
            }
            recallFound(player, record, found, null);
            return;
        }
        FoundItem found = locateOne(record, true, true);
        if (found != null) {
            recallFound(player, record, found, null);
            return;
        }
        if (hasKnownPlayerLikeLocation(record)) {
            player.sendMessage(prefix() + ChatColor.RED + "物品可能正在背包或临时界面同步中，暂不能判定丢失。请稍后再试。 ");
            playSound(player, "error");
            return;
        }
        if (!canCreateLostReplacement(record)) {
            player.sendMessage(prefix() + ChatColor.RED + "暂不能丢失召回：无法确认原物品已消失。请先刷新位置或检查容器。 ");
            playSound(player, "error");
            return;
        }
        double cost = lostRecallCost();
        if (!hasSpace(player)) {
            player.sendMessage(prefix() + ChatColor.RED + "背包没有空位，无法接收召回物。 ");
            playSound(player, "error");
            return;
        }
        if (!withdraw(player, cost)) {
            return;
        }
        if (!giveCreated(player, record)) {
            refund(player, cost, "背包空间在召回过程中发生变化");
            return;
        }
        startRecallCooldown(player);
        player.sendMessage(prefix() + ChatColor.GREEN + "已扣除 " + formatMoney(cost) + "，丢失绑定物已召回。 ");
        playSound(player, "success");
    }

    public void adminRecall(CommandSender sender, BindingRecord record) {
        if (!requireAdmin(sender)) {
            return;
        }
        Player owner = Bukkit.getPlayer(record.getOwnerUuid());
        if (owner == null) {
            sender.sendMessage(prefix() + ChatColor.RED + "绑定者不在线，无法发还物品。 ");
            playSenderSound(sender, "error");
            return;
        }
        FoundItem found = locateOne(record, true, true);
        if (found == null) {
            if (hasKnownPlayerLikeLocation(record)) {
                sender.sendMessage(prefix() + ChatColor.RED + "物品可能正在背包或临时界面同步中，暂不能判定丢失。请稍后再试。 ");
                playSenderSound(sender, "error");
                return;
            }
            if (!canCreateLostReplacement(record)) {
                sender.sendMessage(prefix() + ChatColor.RED + "暂不能丢失召回：无法确认原物品已消失。请先刷新位置或检查容器。 ");
                playSenderSound(sender, "error");
                return;
            }
            if (!hasSpace(owner)) {
                sender.sendMessage(prefix() + ChatColor.RED + "绑定者背包没有空位，无法接收召回物。 ");
                playSenderSound(sender, "error");
                return;
            }
            if (giveCreated(owner, record)) {
                sender.sendMessage(prefix() + ChatColor.GREEN + "已为 " + owner.getName() + " 免费召回丢失绑定物。 ");
                owner.sendMessage(prefix() + ChatColor.GREEN + "管理员已为你召回丢失绑定物。 ");
                playSenderSound(sender, "success");
                playSound(owner, "success");
            } else {
                sender.sendMessage(prefix() + ChatColor.RED + "绑定者背包空间发生变化，本次免费召回已取消。 ");
                playSenderSound(sender, "error");
            }
            return;
        }
        if (!hasSpace(owner) && !(found.getSource() == FoundItem.Source.PLAYER && found.getPlayer().getUniqueId().equals(owner.getUniqueId()))) {
            sender.sendMessage(prefix() + ChatColor.RED + "绑定者背包没有空位，无法接收召回物。 ");
            playSenderSound(sender, "error");
            return;
        }
        recallFound(owner, record, found, sender);
    }

    private void recallLostForAdmin(Player admin, BindingRecord record) {
        Player owner = Bukkit.getPlayer(record.getOwnerUuid());
        if (owner == null) {
            admin.sendMessage(prefix() + ChatColor.RED + "绑定者不在线，无法发还物品。 ");
            playSound(admin, "error");
            return;
        }
        if (!hasSpace(owner)) {
            admin.sendMessage(prefix() + ChatColor.RED + "绑定者背包没有空位，无法接收召回物。 ");
            playSound(admin, "error");
            return;
        }
        if (giveCreated(owner, record)) {
            admin.sendMessage(prefix() + ChatColor.GREEN + "已为 " + owner.getName() + " 免费召回丢失绑定物。 ");
            owner.sendMessage(prefix() + ChatColor.GREEN + "管理员已为你召回丢失绑定物。 ");
            playSound(admin, "success");
            playSound(owner, "success");
        } else {
            admin.sendMessage(prefix() + ChatColor.RED + "绑定者背包空间发生变化，本次免费召回已取消。 ");
            playSound(admin, "error");
        }
    }

    private void recallFound(Player destination, BindingRecord record, FoundItem found, CommandSender adminSender) {
        ItemStack current = found.getItemStack();
        if (isEmpty(current)) {
            destination.sendMessage(prefix() + ChatColor.RED + "召回失败：原物品已移动，请重新扫描后再试。 ");
            playSound(destination, "error");
            return;
        }
        if (found.getSource() == FoundItem.Source.PLAYER && found.getPlayer().getUniqueId().equals(destination.getUniqueId())) {
            ItemStack normalized = applyBinding(current, record);
            found.setItemStack(normalized);
            record.setItem(normalized.clone());
            record.setLocation(BindingLocation.player(destination));
            store.markDirty(record);
            store.saveDirty();
            destination.sendMessage(prefix() + ChatColor.YELLOW + "该绑定物已在你的背包中。 ");
            playSound(destination, "gui.click");
            if (adminSender != null && !adminSender.equals(destination)) {
                adminSender.sendMessage(prefix() + ChatColor.YELLOW + "该绑定物已在 " + destination.getName() + " 的背包中，已刷新绑定记录。 ");
                playSenderSound(adminSender, "gui.click");
            }
            return;
        }
        if (!hasSpace(destination)) {
            destination.sendMessage(prefix() + ChatColor.RED + "背包没有空位，无法接收召回物。 ");
            playSound(destination, "error");
            return;
        }
        ItemStack latest = found.getItemStack();
        Optional<UUID> currentId = getBindingId(latest);
        if (currentId.isEmpty() || !currentId.get().equals(record.getId())) {
            destination.sendMessage(prefix() + ChatColor.RED + "召回失败：原物品位置已变化，请重新打开 GUI 后再试。 ");
            playSound(destination, "error");
            return;
        }
        double cost = adminSender == null ? normalRecallCost() : 0.0D;
        if (!withdraw(destination, cost)) {
            return;
        }
        ItemStack normalized = applyBinding(latest, record);
        logContainerRemoval(found, destination.getName());
        if (!found.remove()) {
            refund(destination, cost, "原物品已移动");
            destination.sendMessage(prefix() + ChatColor.RED + "召回失败：原物品已移动，请重新扫描后再试。 ");
            playSound(destination, "error");
            return;
        }
        int emptySlot = destination.getInventory().firstEmpty();
        if (emptySlot < 0) {
            Item dropped = destination.getWorld().dropItemNaturally(destination.getLocation(), normalized);
            updateDroppedItem(dropped);
            destination.updateInventory();
            if (found.getPlayer() != null) {
                found.getPlayer().updateInventory();
            }
            destination.sendMessage(prefix() + ChatColor.YELLOW + "召回时背包空间发生变化，绑定物已安全掉落在你脚下并继续追踪。 ");
            if (cost > 0.0D) {
                destination.sendMessage(prefix() + ChatColor.YELLOW + "召回费用已扣除：" + formatMoney(cost) + "。 ");
            }
            if (adminSender == null) {
                startRecallCooldown(destination);
            }
            playSound(destination, "success");
            if (adminSender != null && !adminSender.equals(destination)) {
                adminSender.sendMessage(prefix() + ChatColor.GREEN + "绑定物已移动到 " + destination.getName() + " 脚下。 ");
                playSenderSound(adminSender, "success");
            }
            return;
        }
        destination.getInventory().setItem(emptySlot, normalized);
        destination.updateInventory();
        if (found.getSource() == FoundItem.Source.PLAYER) {
            found.getPlayer().updateInventory();
        }
        if (found.getPlayer() != null) {
            found.getPlayer().updateInventory();
        }
        record.setItem(normalized.clone());
        record.setLocation(BindingLocation.player(destination));
        store.markDirty(record);
        store.saveDirty();
        destination.sendMessage(prefix() + ChatColor.GREEN + "绑定物已安全召回。 ");
        if (cost > 0.0D) {
            destination.sendMessage(prefix() + ChatColor.YELLOW + "召回费用已扣除：" + formatMoney(cost) + "。 ");
        }
        if (adminSender == null) {
            startRecallCooldown(destination);
        }
        playSound(destination, "success");
        if (adminSender != null && !adminSender.equals(destination)) {
            adminSender.sendMessage(prefix() + ChatColor.GREEN + "绑定物已发还给 " + destination.getName() + "。 ");
            playSenderSound(adminSender, "success");
        }
    }

    private boolean giveCreated(Player player, BindingRecord record) {
        if (locateOne(record, true, true) != null) {
            player.sendMessage(prefix() + ChatColor.RED + "已取消补发：重新扫描发现原物品仍存在。请重新打开菜单召回。 ");
            playSound(player, "error");
            return false;
        }
        if (isEmpty(record.getItem())) {
            player.sendMessage(prefix() + ChatColor.RED + "召回失败：记录缺少物品快照，不能补发。请联系管理员定位原物品。 ");
            plugin.getLogger().warning("已拒绝生成丢失补发：绑定编号 " + shortId(record.getId()) + " 的物品快照为空。");
            playSound(player, "error");
            return false;
        }
        int emptySlot = player.getInventory().firstEmpty();
        if (emptySlot < 0) {
            player.sendMessage(prefix() + ChatColor.RED + "背包没有空位，无法接收召回物。 ");
            playSound(player, "error");
            return false;
        }
        ItemStack item = applyBinding(record.getItem(), record);
        item.setAmount(1);
        player.getInventory().setItem(emptySlot, item);
        player.updateInventory();
        record.setItem(item.clone());
        record.setLocation(BindingLocation.player(player));
        store.markDirty(record);
        store.saveDirty();
        return true;
    }

    public boolean unbind(CommandSender sender, BindingRecord record, boolean admin) {
        if (admin) {
            if (!requireAdmin(sender)) {
                return false;
            }
        } else if (!requireOwner(sender, record)) {
            return false;
        }
        List<FoundItem> found = findAllOccurrences(record, true, true);
        if (found.isEmpty()) {
            sender.sendMessage(prefix() + ChatColor.RED + "未找到原物品，已取消解除绑定。请先召回或刷新位置。 ");
            playSenderSound(sender, "error");
            return false;
        }
        double cost = !admin && sender instanceof Player ? unbindCost() : 0.0D;
        if (sender instanceof Player player && !withdraw(player, cost)) {
            return false;
        }
        boolean hadDuplicates = uniquePhysicalOccurrences(found).size() > 1;
        FoundItem kept = found.isEmpty() ? null : deduplicate(record, found);
        if (kept != null) {
            ItemStack current = kept.getItemStack();
            if (!isEmpty(current)) {
                kept.setItemStack(removeBinding(current));
            }
        }
        store.remove(record.getId());
        requestEventBackup("解除绑定记录");
        sender.sendMessage(prefix() + ChatColor.GREEN + "已解除绑定。 ");
        if (hadDuplicates) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "检测到重复实例，已先隔离多余物品，仅解除保留实例，避免复制物品被洗白。 ");
        }
        if (cost > 0.0D) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "解除已扣除绑定费用：" + formatMoney(cost) + "。 ");
        }
        playSenderSound(sender, "success");
        return true;
    }

    public void changeOwner(CommandSender sender, BindingRecord record, OfflinePlayer newOwner) {
        String name = newOwner.getName() == null ? newOwner.getUniqueId().toString() : newOwner.getName();
        changeOwner(sender, record, newOwner.getUniqueId(), name);
    }

    public void changeOwner(CommandSender sender, BindingRecord record, UUID newOwnerUuid, String name) {
        if (!requireAdmin(sender)) {
            return;
        }
        record.setOwnerUuid(newOwnerUuid);
        record.setOwnerName(name);
        if (!isEmpty(record.getItem())) {
            record.setItem(applyBinding(record.getItem(), record));
        } else {
            plugin.getLogger().warning("修改绑定者时发现物品快照为空：绑定编号 " + shortId(record.getId()) + "。将尝试通过真实物品重新生成快照。");
        }
        List<FoundItem> found = findAllOccurrences(record, true, true);
        for (FoundItem foundItem : found) {
            ItemStack current = foundItem.getItemStack();
            if (!isEmpty(current)) {
                foundItem.setItemStack(applyBinding(current, record));
            }
        }
        FoundItem kept = deduplicate(record, found);
        if (kept != null) {
            record.setLocation(kept.toBindingLocation());
        } else {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "未找到该绑定物的真实实例，已保留原记录位置；在重新定位前不会生成丢失补发，避免复制风险。 ");
            if (isEmpty(record.getItem())) {
                sender.sendMessage(prefix() + ChatColor.RED + "该记录缺少物品快照，请尽快定位真实物品或从备份恢复记录。 ");
            }
        }
        store.markDirty(record);
        store.saveDirty();
        requestEventBackup("修改绑定者");
        sender.sendMessage(prefix() + ChatColor.GREEN + "绑定者已改为：" + name + "。 ");
        playSenderSound(sender, "success");
    }

    public void setLocked(CommandSender sender, BindingRecord record, boolean locked) {
        if (!requireOwnerOrAdmin(sender, record)) {
            return;
        }
        record.setLocked(locked);
        if (!isEmpty(record.getItem())) {
            record.setItem(applyBinding(record.getItem(), record));
        } else {
            plugin.getLogger().warning("切换不可离包时发现物品快照为空：绑定编号 " + shortId(record.getId()) + "。将尝试通过真实物品重新生成快照。");
        }
        List<FoundItem> found = findAllOccurrences(record, true, true);
        for (FoundItem foundItem : found) {
            ItemStack current = foundItem.getItemStack();
            if (!isEmpty(current)) {
                foundItem.setItemStack(applyBinding(current, record));
            }
        }
        deduplicate(record, found, false);
        store.markDirty(record);
        store.saveDirty();
        requestEventBackup("切换不可离包状态");
        sender.sendMessage(prefix() + ChatColor.GREEN + "不可离包已" + (locked ? "开启" : "关闭") + "。 ");
        if (found.isEmpty() && isEmpty(record.getItem())) {
            sender.sendMessage(prefix() + ChatColor.RED + "注意：该记录缺少物品快照且暂未找到真实物品，后续会继续依赖扫描修复。 ");
        }
        playSenderSound(sender, "success");
    }

    public int setAllLocked(CommandSender sender, UUID ownerUuid, String ownerName, boolean admin) {
        if (ownerUuid == null) {
            sender.sendMessage(prefix() + ChatColor.RED + "无法识别目标玩家，已取消批量保护。 ");
            playSenderSound(sender, "error");
            return 0;
        }
        if (admin) {
            if (!requireAdmin(sender)) {
                return 0;
            }
        } else if (!(sender instanceof Player player) || !player.getUniqueId().equals(ownerUuid)) {
            sender.sendMessage(prefix() + ChatColor.RED + "只能批量保护自己的绑定物。 ");
            playSenderSound(sender, "error");
            return 0;
        }

        List<BindingRecord> changed = new ArrayList<>();
        int missingSnapshots = 0;
        for (BindingRecord record : store.byOwner(ownerUuid)) {
            if (record.isLocked()) {
                continue;
            }
            record.setLocked(true);
            if (isEmpty(record.getItem())) {
                missingSnapshots++;
                plugin.getLogger().warning("批量开启不可离包时发现物品快照为空：绑定编号 " + shortId(record.getId())
                        + "。仍会尝试通过真实物品扫描修复快照。");
            } else {
                record.setItem(applyBinding(record.getItem(), record));
            }
            changed.add(record);
        }

        if (changed.isEmpty()) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "没有需要开启不可离包的绑定物。 ");
            playSenderSound(sender, "gui.disabled");
            return 0;
        }

        Map<UUID, List<FoundItem>> foundById = findInteractiveOccurrencesBatch(changed, true);
        Set<UUID> dirtyRecords = new HashSet<>();
        for (BindingRecord record : changed) {
            dirtyRecords.add(record.getId());
        }
        int unresolvedSnapshots = 0;
        for (BindingRecord record : changed) {
            List<FoundItem> found = foundById.getOrDefault(record.getId(), List.of());
            for (FoundItem foundItem : found) {
                ItemStack current = foundItem.getItemStack();
                if (!isEmpty(current)) {
                    foundItem.setItemStack(applyBinding(current, record));
                }
            }
            deduplicate(record, found, false);
            if (found.isEmpty() && isEmpty(record.getItem())) {
                unresolvedSnapshots++;
            }
        }
        for (UUID dirtyId : dirtyRecords) {
            store.markDirty(dirtyId);
        }
        store.saveDirty();
        requestEventBackup("批量开启不可离包");

        String target = admin ? (ownerName == null ? ownerUuid.toString() : ownerName) + " 的" : "";
        sender.sendMessage(prefix() + ChatColor.GREEN + "已为 " + target + changed.size() + " 件绑定物开启不可离包。 ");
        sender.sendMessage(prefix() + ChatColor.GRAY + "已同步可定位物品，未加载位置会后续校正。 ");
        if (missingSnapshots > 0) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "其中 " + missingSnapshots + " 条记录原本缺少物品快照，已尝试通过真实物品扫描修复。 ");
        }
        if (unresolvedSnapshots > 0) {
            sender.sendMessage(prefix() + ChatColor.RED + "仍有 " + unresolvedSnapshots + " 条记录暂未找到真实物品，快照会在后续定位成功时自动修复。 ");
        }
        playSenderSound(sender, "success");
        return changed.size();
    }

    public FoundItem locateOne(BindingRecord record, boolean loadTrackedContainer) {
        return locateOne(record, loadTrackedContainer, false);
    }

    public FoundItem locateOne(BindingRecord record, boolean loadTrackedContainer, boolean useCoreProtect) {
        List<FoundItem> found = findAllOccurrences(record, loadTrackedContainer, useCoreProtect);
        return deduplicate(record, found);
    }

    public List<FoundItem> findAllOccurrences(BindingRecord record, boolean loadTrackedContainer) {
        return findAllOccurrences(record, loadTrackedContainer, false);
    }

    public List<FoundItem> findAllOccurrences(BindingRecord record, boolean loadTrackedContainer, boolean useCoreProtect) {
        List<FoundItem> found = new ArrayList<>();
        UUID id = record.getId();
        found.addAll(findKnownLocationOccurrences(record));
        if (found.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                scanPlayer(player, id, found);
                scanTemporaryPlayerView(player, id, found);
            }
        }
        if (found.isEmpty() && useCoreProtect && coreProtectHook != null && coreProtectHook.isAvailable()) {
            Set<String> scannedContainers = new HashSet<>();
            int verificationLimit = Math.max(1, plugin.getConfig().getInt("coreprotect.max-candidate-verifications-per-operation", 4));
            int verified = 0;
            for (Location candidate : coreProtectHook.lookupContainerCandidates(record)) {
                if (verified >= verificationLimit) {
                    break;
                }
                verified++;
                Inventory inventory = getLoadedInventoryAt(candidate);
                if (inventory != null) {
                    int before = found.size();
                    scanContainerInventory(inventory, id, found, candidate, null, scannedContainers);
                    if (found.size() > before) {
                        debugCoreProtectLog("已采信 CoreProtect 候选容器：绑定编号 " + shortId(record.getId())
                                + "，位置 " + formatLocation(candidate)
                                + "，并已在真实库存中确认绑定 UUID。");
                    }
                    coreProtectHook.markCandidateVerified(record, candidate);
                } else {
                    scheduleCoreProtectCandidateVerification(record, candidate);
                }
            }
        }
        return found;
    }

    private Map<UUID, List<FoundItem>> findInteractiveOccurrencesBatch(List<BindingRecord> records, boolean useCoreProtect) {
        Map<UUID, List<FoundItem>> found = new HashMap<>();
        Set<UUID> unresolved = new HashSet<>();
        for (BindingRecord record : records) {
            BindingLocation.Type locationType = record.getLocation().getType();
            List<FoundItem> recordFound = locationType == BindingLocation.Type.PLAYER
                    || locationType == BindingLocation.Type.TEMPORARY
                    ? new ArrayList<>()
                    : findKnownLocationOccurrences(record);
            found.put(record.getId(), recordFound);
            if (recordFound.isEmpty()) {
                unresolved.add(record.getId());
            }
        }
        if (!unresolved.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                scanPlayerBatch(player, unresolved, found);
                scanTemporaryPlayerViewBatch(player, unresolved, found);
            }
        }
        if (useCoreProtect && coreProtectHook != null && coreProtectHook.isAvailable()) {
            int remainingQueries = Math.max(0, plugin.getConfig().getInt("coreprotect.max-record-queries-per-operation", 8));
            for (BindingRecord record : records) {
                List<FoundItem> recordFound = found.get(record.getId());
                if (recordFound != null && !recordFound.isEmpty()) {
                    continue;
                }
                if (remainingQueries-- <= 0) {
                    break;
                }
                int verificationLimit = Math.max(1, plugin.getConfig().getInt("coreprotect.max-candidate-verifications-per-operation", 4));
                int verified = 0;
                Set<String> scannedContainers = new HashSet<>();
                for (Location candidate : coreProtectHook.lookupContainerCandidates(record)) {
                    if (verified++ >= verificationLimit) {
                        break;
                    }
                    Inventory inventory = getLoadedInventoryAt(candidate);
                    if (inventory == null) {
                        scheduleCoreProtectCandidateVerification(record, candidate);
                        continue;
                    }
                    scanContainerInventory(inventory, record.getId(), recordFound, candidate, null, scannedContainers);
                    coreProtectHook.markCandidateVerified(record, candidate);
                }
            }
        }
        return found;
    }

    private void scheduleCoreProtectCandidateVerification(BindingRecord record, Location candidate) {
        if (candidate == null || candidate.getWorld() == null || coreProtectHook == null) {
            return;
        }
        String key = record.getId() + ":" + candidate.getWorld().getName() + ":"
                + candidate.getBlockX() + ":" + candidate.getBlockY() + ":" + candidate.getBlockZ();
        if (!pendingCoreProtectCandidateVerifications.add(key)) {
            return;
        }
        UUID recordId = record.getId();
        candidate.getWorld().getChunkAtAsync(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4, false)
                .whenComplete((chunk, throwable) -> {
                    if (!plugin.isEnabled()) {
                        pendingCoreProtectCandidateVerifications.remove(key);
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            Optional<BindingRecord> current = store.find(recordId);
                            if (throwable != null || chunk == null || current.isEmpty()) {
                                return;
                            }
                            List<FoundItem> candidateFound = new ArrayList<>();
                            Inventory inventory = getLoadedInventoryAt(candidate);
                            if (inventory != null) {
                                scanContainerInventory(inventory, recordId, candidateFound, candidate, null, new HashSet<>());
                            }
                            if (!candidateFound.isEmpty()) {
                                deduplicate(current.get(), candidateFound);
                            }
                            coreProtectHook.markCandidateVerified(current.get(), candidate);
                        } finally {
                            pendingCoreProtectCandidateVerifications.remove(key);
                        }
                    });
                });
    }

    private Map<UUID, List<FoundItem>> findAllOccurrencesBatch(List<BindingRecord> records, boolean loadTrackedContainer, boolean useCoreProtect) {
        Map<UUID, List<FoundItem>> found = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (BindingRecord record : records) {
            ids.add(record.getId());
            found.put(record.getId(), new ArrayList<>());
        }
        if (ids.isEmpty()) {
            return found;
        }
        Set<String> scannedContainers = new HashSet<>();
        if (loadTrackedContainer) {
            for (BindingRecord record : records) {
                BindingLocation.Type type = record.getLocation().getType();
                if (type == BindingLocation.Type.DROPPED || type == BindingLocation.Type.CONTAINER) {
                    loadTrackedChunk(record.getLocation());
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanPlayerBatch(player, ids, found);
            scanTemporaryPlayerViewBatch(player, ids, found);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                addFoundAndNested(FoundItem.dropped(item), ids, found, 0);
            }
        }
        scanLoadedBlockContainersBatch(ids, found, scannedContainers);
        scanLoadedEntityContainersBatch(ids, found, scannedContainers);
        scanLoadedArmorStandsBatch(ids, found);
        scanLoadedItemFramesBatch(ids, found);
        scanLoadedItemDisplaysBatch(ids, found);
        if (useCoreProtect && coreProtectHook != null && coreProtectHook.isAvailable()) {
            int remainingQueries = Math.max(0, plugin.getConfig().getInt("coreprotect.max-record-queries-per-operation", 8));
            for (BindingRecord record : records) {
                if (remainingQueries <= 0) {
                    debugCoreProtectLog("本次操作已达到 CoreProtect 记录查询上限，剩余绑定记录将在下次扫描继续。");
                    break;
                }
                List<FoundItem> recordFound = found.get(record.getId());
                if (recordFound != null && !recordFound.isEmpty()) {
                    continue;
                }
                remainingQueries--;
                for (Location candidate : coreProtectHook.lookupContainerCandidates(record)) {
                    Inventory inventory = getLoadedInventoryAt(candidate);
                    if (inventory != null) {
                        int before = recordFound == null ? 0 : recordFound.size();
                        scanContainerInventoryBatch(inventory, ids, found, candidate, null, scannedContainers);
                        recordFound = found.get(record.getId());
                        if (recordFound != null && recordFound.size() > before) {
                            debugCoreProtectLog("已采信 CoreProtect 候选容器：绑定编号 " + shortId(record.getId())
                                    + "，位置 " + formatLocation(candidate)
                                    + "，并已在真实库存中确认绑定 UUID。");
                        }
                    }
                }
            }
        }
        return found;
    }

    private void scanPlayerBatch(Player player, Set<UUID> ids, Map<UUID, List<FoundItem>> found) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            addFoundAndNested(FoundItem.player(player, slot), ids, found, 0);
        }
        Inventory enderChest = player.getEnderChest();
        for (int slot = 0; slot < enderChest.getSize(); slot++) {
            addFoundAndNested(FoundItem.temporary(player, enderChest, slot, "玩家末影箱：" + player.getName()), ids, found, 0);
        }
    }

    private void scanTemporaryPlayerViewBatch(Player player, Set<UUID> ids, Map<UUID, List<FoundItem>> found) {
        addFoundAndNested(FoundItem.cursor(player), ids, found, 0);
        InventoryView view = player.getOpenInventory();
        if (view == null || !isTrackableTemporaryInventory(view.getTopInventory())) {
            return;
        }
        Inventory top = view.getTopInventory();
        if (top.getType() == InventoryType.ENDER_CHEST) {
            return;
        }
        for (int slot : temporaryInputSlots(top)) {
            if (slot >= 0 && slot < top.getSize()) {
                addFoundAndNested(FoundItem.temporary(player, top, slot, temporaryDescription(top.getType(), player)), ids, found, 0);
            }
        }
    }

    private void scanInventoryBatch(Inventory inventory, Set<UUID> ids, Map<UUID, List<FoundItem>> found, Location location, Entity containerEntity) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            FoundItem item = containerEntity == null
                    ? FoundItem.container(inventory, slot, location)
                    : FoundItem.entityContainer(inventory, slot, containerEntity);
            addFoundAndNested(item, ids, found, 0);
        }
    }

    private void scanContainerInventoryBatch(Inventory inventory, Set<UUID> ids, Map<UUID, List<FoundItem>> found, Location location, Entity containerEntity, Set<String> scannedContainers) {
        Location actualLocation = getInventoryLocation(inventory);
        if (actualLocation == null) {
            actualLocation = location;
        }
        String key = containerKey(inventory, actualLocation, containerEntity);
        if (scannedContainers != null && !scannedContainers.add(key)) {
            return;
        }
        scanInventoryBatch(inventory, ids, found, actualLocation, containerEntity);
    }

    private void scanLoadedBlockContainersBatch(Set<UUID> ids, Map<UUID, List<FoundItem>> found, Set<String> scannedContainers) {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof InventoryHolder holder) {
                        Inventory inventory = holder.getInventory();
                        Location location = getInventoryLocation(inventory);
                        if (location != null) {
                            scanContainerInventoryBatch(inventory, ids, found, location, null, scannedContainers);
                        }
                    }
                }
            }
        }
    }

    private void scanLoadedEntityContainersBatch(Set<UUID> ids, Map<UUID, List<FoundItem>> found, Set<String> scannedContainers) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player || !(entity instanceof InventoryHolder holder)) {
                    continue;
                }
                Inventory inventory = holder.getInventory();
                if (isSafeContainerInventory(inventory)) {
                    scanContainerInventoryBatch(inventory, ids, found, entity.getLocation(), entity, scannedContainers);
                }
            }
        }
    }

    private void scanLoadedArmorStandsBatch(Set<UUID> ids, Map<UUID, List<FoundItem>> found) {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand armorStand : world.getEntitiesByClass(ArmorStand.class)) {
                for (EquipmentSlot slot : armorStandSlots()) {
                    addFoundAndNested(FoundItem.armorStand(armorStand, slot), ids, found, 0);
                }
            }
        }
    }

    private void scanLoadedItemFramesBatch(Set<UUID> ids, Map<UUID, List<FoundItem>> found) {
        for (World world : Bukkit.getWorlds()) {
            for (ItemFrame itemFrame : world.getEntitiesByClass(ItemFrame.class)) {
                addFoundAndNested(FoundItem.itemFrame(itemFrame), ids, found, 0);
            }
        }
    }

    private void scanLoadedItemDisplaysBatch(Set<UUID> ids, Map<UUID, List<FoundItem>> found) {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay itemDisplay : world.getEntitiesByClass(ItemDisplay.class)) {
                addFoundAndNested(FoundItem.itemDisplay(itemDisplay), ids, found, 0);
            }
        }
    }

    private void addFoundAndNested(FoundItem item, Set<UUID> ids, Map<UUID, List<FoundItem>> found, int depth) {
        ItemStack stack = item.getItemStack();
        addFoundAndNested(item, stack, ids, found, depth);
    }

    private void addFoundAndNested(FoundItem item, ItemStack stack, Set<UUID> ids, Map<UUID, List<FoundItem>> found, int depth) {
        Optional<UUID> itemId = getBindingId(stack);
        if (itemId.isPresent() && ids.contains(itemId.get())) {
            found.computeIfAbsent(itemId.get(), key -> new ArrayList<>()).add(item);
        }
        scanNestedItemsBatch(item, stack, ids, found, depth);
    }

    private void scanNestedItemsBatch(FoundItem parent, ItemStack container, Set<UUID> ids, Map<UUID, List<FoundItem>> found, int depth) {
        if (depth >= MAX_NESTED_DEPTH || !mayContainNestedItems(container)) {
            return;
        }
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            Inventory nestedInventory = holder.getInventory();
            for (int slot = 0; slot < nestedInventory.getSize(); slot++) {
                addFoundAndNested(FoundItem.nestedBlockInventory(parent, slot), nestedInventory.getItem(slot), ids, found, depth + 1);
            }
        }
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = bundleMeta.getItems();
            for (int slot = 0; slot < items.size(); slot++) {
                addFoundAndNested(FoundItem.nestedBundle(parent, slot), items.get(slot), ids, found, depth + 1);
            }
        }
    }

    private void scanPlayer(Player player, UUID id, List<FoundItem> found) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            FoundItem parent = FoundItem.player(player, slot);
            Optional<UUID> itemId = getBindingId(item);
            if (itemId.isPresent() && itemId.get().equals(id)) {
                found.add(parent);
            }
            scanNestedItem(parent, id, found, 0);
        }
        Inventory enderChest = player.getEnderChest();
        for (int slot = 0; slot < enderChest.getSize(); slot++) {
            ItemStack item = enderChest.getItem(slot);
            FoundItem parent = FoundItem.temporary(player, enderChest, slot, "玩家末影箱：" + player.getName());
            Optional<UUID> itemId = getBindingId(item);
            if (itemId.isPresent() && itemId.get().equals(id)) {
                found.add(parent);
            }
            scanNestedItem(parent, id, found, 0);
        }
    }

    private void scanTemporaryPlayerView(Player player, UUID id, List<FoundItem> found) {
        ItemStack cursor = player.getItemOnCursor();
        Optional<UUID> cursorId = getBindingId(cursor);
        FoundItem cursorFound = FoundItem.cursor(player);
        if (cursorId.isPresent() && cursorId.get().equals(id)) {
            found.add(cursorFound);
        }
        scanNestedItem(cursorFound, id, found, 0);

        InventoryView view = player.getOpenInventory();
        if (view == null || !isTrackableTemporaryInventory(view.getTopInventory())) {
            return;
        }
        Inventory top = view.getTopInventory();
        if (top.getType() == InventoryType.ENDER_CHEST) {
            return;
        }
        for (int slot : temporaryInputSlots(top)) {
            if (slot < 0 || slot >= top.getSize()) {
                continue;
            }
            ItemStack item = top.getItem(slot);
            FoundItem parent = FoundItem.temporary(player, top, slot, temporaryDescription(top.getType(), player));
            Optional<UUID> itemId = getBindingId(item);
            if (itemId.isPresent() && itemId.get().equals(id)) {
                found.add(parent);
            }
            scanNestedItem(parent, id, found, 0);
        }
    }

    private void scanInventory(Inventory inventory, UUID id, List<FoundItem> found, Location location) {
        scanInventory(inventory, id, found, location, null);
    }

    private void scanInventory(Inventory inventory, UUID id, List<FoundItem> found, Location location, Entity containerEntity) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            FoundItem parent = containerEntity == null
                    ? FoundItem.container(inventory, slot, location)
                    : FoundItem.entityContainer(inventory, slot, containerEntity);
            Optional<UUID> itemId = getBindingId(item);
            if (itemId.isPresent() && itemId.get().equals(id)) {
                found.add(parent);
            }
            scanNestedItem(parent, id, found, 0);
        }
    }

    private void scanContainerInventory(Inventory inventory, UUID id, List<FoundItem> found, Location location, Entity containerEntity, Set<String> scannedContainers) {
        Location actualLocation = getInventoryLocation(inventory);
        if (actualLocation == null) {
            actualLocation = location;
        }
        String key = containerKey(inventory, actualLocation, containerEntity);
        if (scannedContainers != null && !scannedContainers.add(key)) {
            return;
        }
        scanInventory(inventory, id, found, actualLocation, containerEntity);
    }

    private void scanLoadedBlockContainers(UUID id, List<FoundItem> found, Set<String> scannedContainers) {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof InventoryHolder holder)) {
                        continue;
                    }
                    Inventory inventory = holder.getInventory();
                    Location location = getInventoryLocation(inventory);
                    if (location != null) {
                        scanContainerInventory(inventory, id, found, location, null, scannedContainers);
                    }
                }
            }
        }
    }

    private void scanLoadedEntityContainers(UUID id, List<FoundItem> found, Set<String> scannedContainers) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player || !(entity instanceof InventoryHolder holder)) {
                    continue;
                }
                Inventory inventory = holder.getInventory();
                if (isSafeContainerInventory(inventory)) {
                    scanContainerInventory(inventory, id, found, entity.getLocation(), entity, scannedContainers);
                }
            }
        }
    }

    private void scanLoadedArmorStands(UUID id, List<FoundItem> found) {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand armorStand : world.getEntitiesByClass(ArmorStand.class)) {
                for (EquipmentSlot slot : armorStandSlots()) {
                    ItemStack item = getArmorStandItem(armorStand, slot);
                    FoundItem parent = FoundItem.armorStand(armorStand, slot);
                    Optional<UUID> itemId = getBindingId(item);
                    if (itemId.isPresent() && itemId.get().equals(id)) {
                        found.add(parent);
                    }
                    scanNestedItem(parent, id, found, 0);
                }
            }
        }
    }

    private void scanLoadedItemFrames(UUID id, List<FoundItem> found) {
        for (World world : Bukkit.getWorlds()) {
            for (ItemFrame itemFrame : world.getEntitiesByClass(ItemFrame.class)) {
                FoundItem parent = FoundItem.itemFrame(itemFrame);
                Optional<UUID> itemId = getBindingId(itemFrame.getItem());
                if (itemId.isPresent() && itemId.get().equals(id)) {
                    found.add(parent);
                }
                scanNestedItem(parent, id, found, 0);
            }
        }
    }

    private void scanLoadedItemDisplays(UUID id, List<FoundItem> found) {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay itemDisplay : world.getEntitiesByClass(ItemDisplay.class)) {
                FoundItem parent = FoundItem.itemDisplay(itemDisplay);
                Optional<UUID> itemId = getBindingId(itemDisplay.getItemStack());
                if (itemId.isPresent() && itemId.get().equals(id)) {
                    found.add(parent);
                }
                scanNestedItem(parent, id, found, 0);
            }
        }
    }

    private void scanNestedItem(FoundItem parent, UUID id, List<FoundItem> found, int depth) {
        ItemStack container = parent.getItemStack();
        scanNestedItem(parent, container, id, found, depth);
    }

    private void scanNestedItem(FoundItem parent, ItemStack container, UUID id, List<FoundItem> found, int depth) {
        if (depth >= MAX_NESTED_DEPTH || !mayContainNestedItems(container)) {
            return;
        }
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            Inventory nestedInventory = holder.getInventory();
            for (int slot = 0; slot < nestedInventory.getSize(); slot++) {
                FoundItem nested = FoundItem.nestedBlockInventory(parent, slot);
                ItemStack item = nestedInventory.getItem(slot);
                Optional<UUID> itemId = getBindingId(item);
                if (itemId.isPresent() && itemId.get().equals(id)) {
                    found.add(nested);
                }
                scanNestedItem(nested, item, id, found, depth + 1);
            }
        }
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = bundleMeta.getItems();
            for (int slot = 0; slot < items.size(); slot++) {
                FoundItem nested = FoundItem.nestedBundle(parent, slot);
                ItemStack item = items.get(slot);
                Optional<UUID> itemId = getBindingId(item);
                if (itemId.isPresent() && itemId.get().equals(id)) {
                    found.add(nested);
                }
                scanNestedItem(nested, item, id, found, depth + 1);
            }
        }
    }

    private String containerKey(Inventory inventory, Location location, Entity containerEntity) {
        if (containerEntity != null) {
            return "实体:" + containerEntity.getUniqueId();
        }
        Location actual = location == null ? getInventoryLocation(inventory) : location;
        if (actual != null && actual.getWorld() != null) {
            return "方块:" + actual.getWorld().getName() + ":" + actual.getBlockX() + ":" + actual.getBlockY() + ":" + actual.getBlockZ();
        }
        return "未知:" + System.identityHashCode(inventory);
    }

    private FoundItem deduplicate(BindingRecord record, List<FoundItem> found) {
        return deduplicate(record, found, true);
    }

    private FoundItem deduplicate(BindingRecord record, List<FoundItem> found, boolean save) {
        if (found.isEmpty()) {
            return null;
        }
        List<FoundItem> unique = uniquePhysicalOccurrences(found);
        FoundItem keep = chooseKeep(record, unique);
        ItemStack keepStack = keep.getItemStack();
        if (!isEmpty(keepStack)) {
            ItemStack normalized = applyBinding(keepStack, record);
            keep.setItemStack(normalized);
            record.setItem(normalized.clone());
            record.setLocation(keep.toBindingLocation());
        }
        if (unique.size() > 1) {
            for (FoundItem duplicate : unique) {
                if (duplicate == keep) {
                    continue;
                }
                quarantineDuplicate(record, duplicate, keep);
            }
            plugin.getLogger().warning("检测到绑定物重复实例，已隔离多余物品，保留位置：" + keep.describe() + "。绑定编号：" + shortId(record.getId()));
        }
        if (save) {
            store.markDirty(record);
            store.saveDirty();
        }
        return keep;
    }

    private void quarantineDuplicate(BindingRecord record, FoundItem duplicate, FoundItem keep) {
        ItemStack duplicateStack = duplicate.getItemStack();
        if (isEmpty(duplicateStack)) {
            return;
        }
        if (!saveQuarantineRecord(record, duplicateStack.clone(), duplicate.describe(), keep.describe())) {
            plugin.getLogger().warning("重复物隔离记录保存失败，已保留现场实例以便人工处理：绑定编号 "
                    + shortId(record.getId()) + "，位置：" + duplicate.describe() + "。");
            return;
        }
        if (!duplicate.remove()) {
            plugin.getLogger().warning("重复实例已写入隔离记录，但现场移除失败：绑定编号 " + shortId(record.getId()) + "，位置：" + duplicate.describe() + "。");
        } else {
            requestEventBackup("重复实例隔离");
        }
    }

    private boolean saveQuarantineRecord(BindingRecord record, ItemStack item, String duplicateLocation, String keepLocation) {
        File file = new File(plugin.getDataFolder(), "quarantine.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = configuration.getConfigurationSection("items");
        if (root == null) {
            root = configuration.createSection("items");
        }
        String key = System.currentTimeMillis() + "-" + shortId(record.getId()) + "-" + Integer.toHexString(Math.abs(duplicateLocation.hashCode()));
        ConfigurationSection section = root.createSection(key);
        section.set("binding-id", record.getId().toString());
        section.set("owner", record.getOwnerUuid().toString());
        section.set("owner-name", record.getOwnerName());
        section.set("item", item);
        section.set("duplicate-location", duplicateLocation);
        section.set("kept-location", keepLocation);
        section.set("created-at", System.currentTimeMillis());
        section.set("reason", "检测到相同绑定 UUID 的重复实例；为避免复制风险，已从世界中隔离。");
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录，重复物隔离记录未保存。");
                return false;
            }
            configuration.save(file);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存重复物隔离记录失败。", ex);
            return false;
        }
    }

    private List<FoundItem> uniquePhysicalOccurrences(List<FoundItem> found) {
        List<FoundItem> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FoundItem item : found) {
            if (seen.add(item.identityKey())) {
                unique.add(item);
            }
        }
        return unique;
    }

    private FoundItem chooseKeep(BindingRecord record, List<FoundItem> found) {
        for (FoundItem item : found) {
            if (matchesRecordedLocation(record.getLocation(), item)) {
                return item;
            }
        }
        for (FoundItem item : found) {
            if (item.getPlayer() != null && item.getPlayer().getUniqueId().equals(record.getOwnerUuid())) {
                return item;
            }
        }
        return found.get(0);
    }

    private boolean matchesRecordedLocation(BindingLocation location, FoundItem item) {
        if (location.getType() == BindingLocation.Type.PLAYER && item.getSource() == FoundItem.Source.PLAYER) {
            return item.getPlayer().getUniqueId().equals(location.getHolderUuid());
        }
        if (location.getType() == BindingLocation.Type.TEMPORARY
                && (item.getSource() == FoundItem.Source.ANVIL || item.getSource() == FoundItem.Source.TEMPORARY || item.getSource() == FoundItem.Source.CURSOR || item.getSource() == FoundItem.Source.NESTED)) {
            return item.getPlayer() != null && item.getPlayer().getUniqueId().equals(location.getHolderUuid());
        }
        if (location.getType() == BindingLocation.Type.DROPPED && item.getSource() == FoundItem.Source.DROPPED) {
            return location.getEntityUuid() != null && location.getEntityUuid().equals(item.getEntity().getUniqueId());
        }
        if (location.getType() == BindingLocation.Type.CONTAINER
                && (item.getSource() == FoundItem.Source.CONTAINER || item.getSource() == FoundItem.Source.ARMOR_STAND || item.getSource() == FoundItem.Source.ITEM_FRAME || item.getSource() == FoundItem.Source.ITEM_DISPLAY || item.getSource() == FoundItem.Source.NESTED)) {
            if (location.getEntityUuid() != null) {
                return item.getContainerEntity() != null && location.getEntityUuid().equals(item.getContainerEntity().getUniqueId());
            }
            Location itemLocation = item.getContainerLocation();
            return itemLocation != null
                    && itemLocation.getWorld() != null
                    && location.getWorld() != null
                    && location.getWorld().equals(itemLocation.getWorld().getName())
                    && location.getX() == itemLocation.getBlockX()
                    && location.getY() == itemLocation.getBlockY()
                    && location.getZ() == itemLocation.getBlockZ();
        }
        return false;
    }

    public boolean scanAndCleanAll() {
        List<BindingRecord> records = boundedScanRecords(store.all(), "binding.manual-scan-max-records", 512);
        if (records.isEmpty()) {
            return false;
        }
        Map<UUID, List<FoundItem>> foundById = findAllOccurrencesBatch(records, true, useCoreProtectInFullScan());
        for (BindingRecord record : records) {
            ItemStack beforeItem = cloneOrNull(record.getItem());
            BindingLocation beforeLocation = record.getLocation();
            FoundItem found = deduplicate(record, foundById.getOrDefault(record.getId(), List.of()), false);
            if (found != null && recordChanged(record, beforeItem, beforeLocation)) {
                store.markDirty(record);
            }
            if (found == null
                    && record.getLocation().getType() == BindingLocation.Type.DROPPED
                    && isDroppedLocationVerifiedMissing(record.getLocation())) {
                record.setLocation(BindingLocation.lost());
                store.markDirty(record);
                continue;
            }
        }
        store.saveDirty();
        return records.size() < store.all().size();
    }

    public boolean startIncrementalFullScan(CommandSender sender, Runnable completion) {
        if (fullScanTask != null) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "全服安全扫描已在分批执行中，请等待本轮完成。 ");
            return false;
        }
        List<BindingRecord> records = store.all();
        if (records.isEmpty()) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "当前没有绑定记录，无需执行全服安全扫描。 ");
            return false;
        }
        fullScanCursor = 0;
        int recordsPerRun = Math.max(1, plugin.getConfig().getInt("binding.manual-scan-records-per-run", 32));
        long intervalTicks = Math.max(1L, plugin.getConfig().getLong("binding.manual-scan-interval-ticks", 5L));
        sender.sendMessage(prefix() + ChatColor.YELLOW + "全服安全扫描已改为分批执行：每轮最多 " + recordsPerRun + " 条，间隔 " + intervalTicks + " tick，避免服务器瞬时卡顿。 ");
        fullScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> runIncrementalFullScanBatch(sender, recordsPerRun, completion), 1L, intervalTicks);
        return true;
    }

    private List<BindingRecord> boundedScanRecords(List<BindingRecord> records, String configPath, int fallback) {
        int limit = Math.max(0, plugin.getConfig().getInt(configPath, fallback));
        if (limit <= 0 || records.size() <= limit) {
            return records;
        }
        if (fullScanCursor < 0 || fullScanCursor >= records.size()) {
            fullScanCursor = 0;
        }
        List<BindingRecord> selected = new ArrayList<>();
        for (int processed = 0; processed < limit; processed++) {
            selected.add(records.get(fullScanCursor));
            fullScanCursor++;
            if (fullScanCursor >= records.size()) {
                fullScanCursor = 0;
            }
        }
        return selected;
    }

    private void runIncrementalFullScanBatch(CommandSender sender, int recordsPerRun, Runnable completion) {
        List<BindingRecord> allRecords = store.all();
        if (allRecords.isEmpty() || fullScanCursor >= allRecords.size()) {
            finishIncrementalFullScan(sender, completion);
            return;
        }
        int end = Math.min(allRecords.size(), fullScanCursor + recordsPerRun);
        List<BindingRecord> batch = new ArrayList<>(allRecords.subList(fullScanCursor, end));
        fullScanCursor = end;
        Map<UUID, List<FoundItem>> foundById = findAllOccurrencesBatch(batch, true, useCoreProtectInFullScan());
        boolean dirty = false;
        for (BindingRecord record : batch) {
            ItemStack beforeItem = cloneOrNull(record.getItem());
            BindingLocation beforeLocation = record.getLocation();
            FoundItem found = deduplicate(record, foundById.getOrDefault(record.getId(), List.of()), false);
            if (found != null && recordChanged(record, beforeItem, beforeLocation)) {
                store.markDirty(record);
                dirty = true;
            }
            if (found == null
                    && record.getLocation().getType() == BindingLocation.Type.DROPPED
                    && isDroppedLocationVerifiedMissing(record.getLocation())) {
                record.setLocation(BindingLocation.lost());
                store.markDirty(record);
                dirty = true;
            }
        }
        if (dirty) {
            store.saveDirty();
        }
        if (fullScanCursor >= allRecords.size()) {
            finishIncrementalFullScan(sender, completion);
        }
    }

    private void finishIncrementalFullScan(CommandSender sender, Runnable completion) {
        if (fullScanTask != null) {
            fullScanTask.cancel();
            fullScanTask = null;
        }
        sender.sendMessage(prefix() + ChatColor.GREEN + "分批全服安全扫描已完成，重复实例会按隔离规则处理。 ");
        if (completion != null) {
            completion.run();
        }
    }

    public void scanAndCleanAutomatic() {
        List<BindingRecord> records = store.all();
        if (records.isEmpty()) {
            automaticScanCursor = 0;
            return;
        }
        int maxRecords = Math.max(1, plugin.getConfig().getInt("binding.scan-records-per-run", 64));
        long budgetNanos = Math.max(1L, plugin.getConfig().getLong("binding.scan-time-budget-ms", 10L)) * 1_000_000L;
        long deadline = System.nanoTime() + budgetNanos;
        int maxThisRun = Math.min(maxRecords, records.size());
        int processed = 0;
        boolean dirty = false;
        while (processed < maxThisRun) {
            if (processed > 0 && System.nanoTime() >= deadline) {
                break;
            }
            if (automaticScanCursor < 0 || automaticScanCursor >= records.size()) {
                automaticScanCursor = 0;
            }
            BindingRecord record = records.get(automaticScanCursor);
            automaticScanCursor++;
            if (automaticScanCursor >= records.size()) {
                automaticScanCursor = 0;
            }
            dirty |= scanAndCleanKnownLocation(record);
            processed++;
        }
        if (dirty) {
            store.saveDirty();
        }
    }

    private boolean scanAndCleanKnownLocation(BindingRecord record) {
        ItemStack beforeItem = cloneOrNull(record.getItem());
        BindingLocation beforeLocation = record.getLocation();
        List<FoundItem> foundItems = findKnownLocationOccurrences(record);
        FoundItem found = deduplicate(record, foundItems, false);
        boolean dirty = found != null && recordChanged(record, beforeItem, beforeLocation);
        if (found == null
                && record.getLocation().getType() == BindingLocation.Type.DROPPED
                && isDroppedLocationVerifiedMissing(record.getLocation())) {
            record.setLocation(BindingLocation.lost());
            dirty = true;
        }
        if (dirty) {
            store.markDirty(record);
        }
        return dirty;
    }

    private List<FoundItem> findKnownLocationOccurrences(BindingRecord record) {
        List<FoundItem> found = new ArrayList<>();
        BindingLocation location = record.getLocation();
        if (location == null) {
            return found;
        }
        switch (location.getType()) {
            case PLAYER, TEMPORARY -> scanKnownPlayerLocation(record, found);
            case CONTAINER -> scanKnownContainerLocation(record, found);
            case DROPPED -> scanKnownDroppedLocation(record, found);
            case LOST -> {
                // 已丢失记录不参与自动轻量扫描，避免无意义的全服查找。
            }
        }
        return found;
    }

    private void scanKnownPlayerLocation(BindingRecord record, List<FoundItem> found) {
        BindingLocation location = record.getLocation();
        UUID holderUuid = location.getHolderUuid() == null ? record.getOwnerUuid() : location.getHolderUuid();
        Player player = Bukkit.getPlayer(holderUuid);
        if (player == null) {
            return;
        }
        scanPlayer(player, record.getId(), found);
        scanTemporaryPlayerView(player, record.getId(), found);
    }

    private void scanKnownContainerLocation(BindingRecord record, List<FoundItem> found) {
        BindingLocation location = record.getLocation();
        if (!isTrackedChunkLoaded(location)) {
            return;
        }
        UUID id = record.getId();
        if (location.getEntityUuid() != null) {
            Entity entity = getLoadedKnownEntity(location);
            if (entity instanceof ArmorStand armorStand) {
                for (EquipmentSlot slot : armorStandSlots()) {
                    addFoundAndNested(FoundItem.armorStand(armorStand, slot), getArmorStandItem(armorStand, slot), id, found, 0);
                }
                return;
            }
            if (entity instanceof ItemFrame itemFrame) {
                addFoundAndNested(FoundItem.itemFrame(itemFrame), itemFrame.getItem(), id, found, 0);
                return;
            }
            if (entity instanceof ItemDisplay itemDisplay) {
                addFoundAndNested(FoundItem.itemDisplay(itemDisplay), itemDisplay.getItemStack(), id, found, 0);
                return;
            }
            if (entity instanceof InventoryHolder holder) {
                Inventory inventory = holder.getInventory();
                if (isSafeContainerInventory(inventory)) {
                    scanContainerInventory(inventory, id, found, entity.getLocation(), entity, new HashSet<>());
                }
            }
            return;
        }
        Inventory inventory = getLoadedInventoryAt(location);
        Location blockLocation = location.toBlockLocation();
        if (inventory != null && blockLocation != null) {
            scanContainerInventory(inventory, id, found, blockLocation, null, new HashSet<>());
        }
    }

    private void scanKnownDroppedLocation(BindingRecord record, List<FoundItem> found) {
        BindingLocation location = record.getLocation();
        if (location.getEntityUuid() == null || !isTrackedChunkLoaded(location)) {
            return;
        }
        Entity entity = Bukkit.getEntity(location.getEntityUuid());
        if (entity instanceof Item item) {
            addFoundAndNested(FoundItem.dropped(item), item.getItemStack(), record.getId(), found, 0);
        }
    }

    private void addFoundAndNested(FoundItem item, ItemStack stack, UUID id, List<FoundItem> found, int depth) {
        Optional<UUID> itemId = getBindingId(stack);
        if (itemId.isPresent() && itemId.get().equals(id)) {
            found.add(item);
        }
        scanNestedItem(item, stack, id, found, depth);
    }

    private Entity getLoadedKnownEntity(BindingLocation location) {
        if (location.getEntityUuid() == null || !isTrackedChunkLoaded(location)) {
            return null;
        }
        Entity entity = Bukkit.getEntity(location.getEntityUuid());
        if (entity == null || entity instanceof Player || !entity.isValid()) {
            return null;
        }
        return entity;
    }

    private Inventory getLoadedInventoryAt(BindingLocation location) {
        return getLoadedInventoryAt(location.toBlockLocation());
    }

    private Inventory getLoadedInventoryAt(Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return null;
        }
        World world = blockLocation.getWorld();
        if (!world.isChunkLoaded(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4)) {
            return null;
        }
        BlockState state = world.getBlockAt(blockLocation).getState();
        if (!(state instanceof InventoryHolder holder)) {
            return null;
        }
        Inventory inventory = holder.getInventory();
        return isSafeContainerInventory(inventory) ? inventory : null;
    }

    private void loadTrackedChunk(BindingLocation location) {
        Location blockLocation = location.toBlockLocation();
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return;
        }
        blockLocation.getWorld().loadChunk(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4);
    }

    private boolean isTrackedChunkLoaded(BindingLocation location) {
        Location blockLocation = location.toBlockLocation();
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return true;
        }
        return blockLocation.getWorld().isChunkLoaded(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4);
    }

    private boolean isDroppedLocationVerifiedMissing(BindingLocation location) {
        return location != null
                && location.getType() == BindingLocation.Type.DROPPED
                && location.toBlockLocation() != null
                && isTrackedChunkLoaded(location);
    }

    public void updatePlayerLocations(Player player) {
        boolean dirty = updatePlayerInventoryLocation(player);
        dirty |= updateEnderChestLocation(player);
        dirty |= updateCursorLocation(player);
        if (dirty) {
            store.saveDirty();
        }
    }

    public void updateInventoryInteractionLocations(Player player, Inventory top, Set<Integer> rawSlots, Set<Integer> playerSlots, boolean includeCursor, boolean fullPlayer) {
        boolean dirty = false;
        if (fullPlayer) {
            dirty |= updatePlayerInventoryLocation(player);
            dirty |= updateTopInventoryLocation(player, top);
        } else {
            for (int slot : playerSlots) {
                dirty |= updatePlayerInventorySlotLocation(player, slot);
            }
            for (int rawSlot : rawSlots) {
                if (rawSlot < 0) {
                    continue;
                }
                if (top != null && rawSlot < top.getSize()) {
                    dirty |= updateTopInventorySlotLocation(player, top, rawSlot);
                } else if (top != null) {
                    dirty |= updatePlayerInventorySlotLocation(player, rawSlot - top.getSize());
                }
            }
        }
        if (includeCursor) {
            dirty |= updateCursorLocation(player);
        }
        if (dirty) {
            store.saveDirty();
        }
    }

    private boolean updatePlayerInventoryLocation(Player player) {
        boolean dirty = false;
        PlayerInventory inventory = player.getInventory();
        BindingLocation location = BindingLocation.player(player);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int currentSlot = slot;
            dirty |= updateItemStackLocation(inventory.getItem(currentSlot), location, item -> inventory.setItem(currentSlot, item), 0);
        }
        return dirty;
    }

    private boolean updatePlayerInventorySlotLocation(Player player, int slot) {
        PlayerInventory inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getSize()) {
            return false;
        }
        BindingLocation location = BindingLocation.player(player);
        return updateItemStackLocation(inventory.getItem(slot), location, item -> inventory.setItem(slot, item), 0);
    }

    private boolean updateEnderChestLocation(Player player) {
        boolean dirty = false;
        Inventory enderChest = player.getEnderChest();
        BindingLocation enderLocation = BindingLocation.temporary(player, "玩家末影箱：" + player.getName());
        for (int slot = 0; slot < enderChest.getSize(); slot++) {
            int currentSlot = slot;
            dirty |= updateItemStackLocation(enderChest.getItem(currentSlot), enderLocation, item -> enderChest.setItem(currentSlot, item), 0);
        }
        return dirty;
    }

    private boolean updateCursorLocation(Player player) {
        BindingLocation cursorLocation = BindingLocation.temporary(player, "玩家鼠标：" + player.getName());
        return updateItemStackLocation(player.getItemOnCursor(), cursorLocation, player::setItemOnCursor, 0);
    }

    private boolean updateTopInventoryLocation(Player player, Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        if (isSafeContainerInventory(inventory)) {
            return updateContainerLocationsWithoutSave(inventory);
        }
        if (!isTrackableTemporaryInventory(inventory)) {
            return false;
        }
        return updateTemporaryLocationsWithoutSave(player, inventory);
    }

    private boolean updateTopInventorySlotLocation(Player player, Inventory inventory, int slot) {
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
            return false;
        }
        if (isSafeContainerInventory(inventory)) {
            Location location = getInventoryLocation(inventory);
            Entity containerEntity = getInventoryContainerEntity(inventory);
            BindingLocation bindingLocation = containerEntity == null ? BindingLocation.container(location) : BindingLocation.entityContainer(containerEntity);
            return updateItemStackLocation(inventory.getItem(slot), bindingLocation, item -> inventory.setItem(slot, item), 0);
        }
        if (!isTrackableTemporaryInventory(inventory)) {
            return false;
        }
        BindingLocation location = BindingLocation.temporary(player, temporaryDescription(inventory.getType(), player));
        return updateItemStackLocation(inventory.getItem(slot), location, item -> inventory.setItem(slot, item), 0);
    }

    public void updateContainerLocations(Inventory inventory) {
        boolean dirty = updateContainerLocationsWithoutSave(inventory);
        if (dirty) {
            store.saveDirty();
        }
    }

    private boolean updateContainerLocationsWithoutSave(Inventory inventory) {
        Location location = getInventoryLocation(inventory);
        if (location == null) {
            return false;
        }
        Entity containerEntity = getInventoryContainerEntity(inventory);
        BindingLocation bindingLocation = containerEntity == null ? BindingLocation.container(location) : BindingLocation.entityContainer(containerEntity);
        boolean dirty = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int currentSlot = slot;
            dirty |= updateItemStackLocation(inventory.getItem(currentSlot), bindingLocation, item -> inventory.setItem(currentSlot, item), 0);
        }
        return dirty;
    }

    public void updateTemporaryLocations(Player player, Inventory inventory) {
        boolean dirty = updateTemporaryLocationsWithoutSave(player, inventory);
        if (dirty) {
            store.saveDirty();
        }
    }

    private boolean updateTemporaryLocationsWithoutSave(Player player, Inventory inventory) {
        if (!isTrackableTemporaryInventory(inventory)) {
            return false;
        }
        BindingLocation location = BindingLocation.temporary(player, temporaryDescription(inventory.getType(), player));
        boolean dirty = false;
        for (int slot : temporaryInputSlots(inventory)) {
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            int currentSlot = slot;
            dirty |= updateItemStackLocation(inventory.getItem(currentSlot), location, item -> inventory.setItem(currentSlot, item), 0);
        }
        return dirty;
    }

    public void updateArmorStandLocations(ArmorStand armorStand) {
        boolean dirty = false;
        BindingLocation location = BindingLocation.entityContainer(armorStand);
        for (EquipmentSlot slot : armorStandSlots()) {
            EquipmentSlot currentSlot = slot;
            dirty |= updateItemStackLocation(getArmorStandItem(armorStand, currentSlot), location, item -> setArmorStandItem(armorStand, currentSlot, item), 0);
        }
        if (dirty) {
            store.saveDirty();
        }
    }

    public boolean hasBoundArmorStandItem(ArmorStand armorStand) {
        for (EquipmentSlot slot : armorStandSlots()) {
            if (containsBoundDeep(getArmorStandItem(armorStand, slot))) {
                return true;
            }
        }
        return false;
    }

    public void updateItemFrameLocation(ItemFrame itemFrame) {
        BindingLocation location = BindingLocation.entityContainer(itemFrame);
        boolean dirty = updateItemStackLocation(itemFrame.getItem(), location, item -> itemFrame.setItem(item, false), 0);
        if (dirty) {
            store.saveDirty();
        }
    }

    public boolean hasBoundItemFrameItem(ItemFrame itemFrame) {
        return itemFrame != null && containsBoundDeep(itemFrame.getItem());
    }

    public boolean hasBoundItemDisplayItem(ItemDisplay itemDisplay) {
        return itemDisplay != null && containsBoundDeep(itemDisplay.getItemStack());
    }

    public void updateDroppedItem(Item item) {
        BindingLocation location = BindingLocation.dropped(item);
        boolean dirty = updateItemStackLocation(item.getItemStack(), location, item::setItemStack, 0);
        if (dirty) {
            store.saveDirty();
        }
    }

    public void markLost(ItemStack item, String reason) {
        getBindingId(item).ifPresent(id -> markLost(id, reason));
    }

    public void markLostDeep(ItemStack item, String reason) {
        boolean dirty = false;
        for (UUID id : bindingIdsDeep(item)) {
            Optional<BindingRecord> optional = store.find(id);
            if (optional.isEmpty()) {
                continue;
            }
            BindingRecord record = optional.get();
            if (record.getLocation().getType() == BindingLocation.Type.LOST) {
                continue;
            }
            record.setLocation(BindingLocation.lost());
            store.markDirty(record);
            dirty = true;
            plugin.getLogger().info("绑定物已标记为丢失：" + shortId(id) + "，原因：" + reason + "。 ");
        }
        if (dirty) {
            store.saveDirty();
        }
    }

    private boolean updateFoundItemLocation(FoundItem foundItem, BindingLocation location) {
        ItemStack item = foundItem.getItemStack();
        Optional<UUID> id = getBindingId(item);
        if (id.isEmpty()) {
            return false;
        }
        Optional<BindingRecord> optional = store.find(id.get());
        if (optional.isEmpty()) {
            return false;
        }
        BindingRecord record = optional.get();
        ItemStack normalized = applyBinding(item, record);
        boolean itemNeedsRewrite = !normalized.equals(item);
        boolean recordChanged = !Objects.equals(record.getItem(), normalized)
                || !Objects.equals(record.getLocation(), location);
        if (itemNeedsRewrite) {
            foundItem.setItemStack(normalized);
        }
        if (recordChanged) {
            record.setItem(normalized.clone());
            record.setLocation(location);
            store.markDirty(record);
        }
        return recordChanged;
    }

    private boolean updateItemStackLocation(ItemStack item, BindingLocation location, Consumer<ItemStack> writer, int depth) {
        if (isEmpty(item)) {
            return false;
        }
        boolean dirty = false;
        ItemStack current = item;
        BindingRecord currentRecord = null;
        Optional<UUID> id = getBindingId(current);
        if (id.isPresent()) {
            Optional<BindingRecord> optional = store.find(id.get());
            if (optional.isPresent()) {
                currentRecord = optional.get();
                ItemStack normalized = applyBinding(current, currentRecord);
                if (!normalized.equals(current)) {
                    writer.accept(normalized);
                    current = normalized;
                }
                if (!Objects.equals(currentRecord.getItem(), current)
                        || !Objects.equals(currentRecord.getLocation(), location)) {
                    currentRecord.setItem(current.clone());
                    currentRecord.setLocation(location);
                    store.markDirty(currentRecord);
                    dirty = true;
                }
            }
        }
        if (depth >= MAX_NESTED_DEPTH || !mayContainNestedItems(current)) {
            return dirty;
        }
        ItemMeta meta = current.getItemMeta();
        if (meta == null) {
            return dirty;
        }
        boolean containerChanged = false;
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            Inventory nestedInventory = holder.getInventory();
            boolean nestedDirty = false;
            for (int slot = 0; slot < nestedInventory.getSize(); slot++) {
                int currentSlot = slot;
                nestedDirty |= updateItemStackLocation(nestedInventory.getItem(currentSlot), location, nested -> nestedInventory.setItem(currentSlot, nested), depth + 1);
            }
            if (nestedDirty) {
                blockStateMeta.setBlockState((BlockState) holder);
                ItemStack updated = current.clone();
                updated.setItemMeta(blockStateMeta);
                writer.accept(updated);
                current = updated;
                containerChanged = true;
                dirty = true;
            }
        } else if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = new ArrayList<>(bundleMeta.getItems());
            boolean nestedDirty = false;
            for (int slot = 0; slot < items.size(); slot++) {
                int currentSlot = slot;
                nestedDirty |= updateItemStackLocation(items.get(currentSlot), location, nested -> items.set(currentSlot, nested), depth + 1);
            }
            if (nestedDirty) {
                bundleMeta.setItems(items);
                ItemStack updated = current.clone();
                updated.setItemMeta(bundleMeta);
                writer.accept(updated);
                current = updated;
                containerChanged = true;
                dirty = true;
            }
        }
        if (containerChanged && currentRecord != null && !Objects.equals(currentRecord.getItem(), current)) {
            currentRecord.setItem(current.clone());
            store.markDirty(currentRecord);
        }
        return dirty;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private boolean recordChanged(BindingRecord record, ItemStack beforeItem, BindingLocation beforeLocation) {
        return !Objects.equals(beforeItem, record.getItem())
                || !Objects.equals(beforeLocation, record.getLocation());
    }

    private boolean updateNestedFoundItemLocations(FoundItem parent, BindingLocation location, int depth) {
        ItemStack container = parent.getItemStack();
        if (depth >= MAX_NESTED_DEPTH || !mayContainNestedItems(container)) {
            return false;
        }
        boolean dirty = false;
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            Inventory nestedInventory = holder.getInventory();
            for (int slot = 0; slot < nestedInventory.getSize(); slot++) {
                FoundItem nested = FoundItem.nestedBlockInventory(parent, slot);
                dirty |= updateFoundItemLocation(nested, location);
                dirty |= updateNestedFoundItemLocations(nested, location, depth + 1);
            }
        }
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = bundleMeta.getItems();
            for (int slot = 0; slot < items.size(); slot++) {
                FoundItem nested = FoundItem.nestedBundle(parent, slot);
                dirty |= updateFoundItemLocation(nested, location);
                dirty |= updateNestedFoundItemLocations(nested, location, depth + 1);
            }
        }
        return dirty;
    }

    public void markLost(UUID id, String reason) {
        Optional<BindingRecord> optional = store.find(id);
        if (optional.isEmpty()) {
            return;
        }
        BindingRecord record = optional.get();
        if (record.getLocation().getType() == BindingLocation.Type.LOST) {
            return;
        }
        record.setLocation(BindingLocation.lost());
        store.markDirty(record);
        store.saveDirty();
        plugin.getLogger().info("绑定物已标记为丢失：" + shortId(id) + "，原因：" + reason + "。 ");
    }

    public void dropBoundItemsFromContainer(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder holder)) {
            return;
        }
        Inventory inventory = state instanceof Chest chest ? chest.getBlockInventory() : holder.getInventory();
        if (!isSafeContainerInventory(inventory)) {
            return;
        }
        Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!containsBoundDeep(item)) {
                continue;
            }
            logContainerTransaction("Binder", block.getLocation());
            inventory.setItem(slot, null);
            Item dropped = block.getWorld().dropItemNaturally(dropLocation, item);
            updateDroppedItem(dropped);
        }
    }

    public void returnItemToOwnerInventory(Player player, ItemStack item) {
        Optional<UUID> id = getBindingId(item);
        if (id.isEmpty()) {
            if (!containsBoundDeep(item)) {
                return;
            }
            ItemStack container = item.clone();
            if (!hasSpace(player)) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), container);
                updateDroppedItem(dropped);
                player.sendMessage(prefix() + ChatColor.YELLOW + "背包已满，包含不可离包绑定物的收纳物已安全掉落在脚下，请尽快拾取。 ");
                playSound(player, "error");
                return;
            }
            int emptySlot = player.getInventory().firstEmpty();
            if (emptySlot < 0) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), container);
                updateDroppedItem(dropped);
                player.sendMessage(prefix() + ChatColor.YELLOW + "背包空间刚刚发生变化，包含不可离包绑定物的收纳物已安全掉落在脚下。 ");
                playSound(player, "error");
                return;
            }
            player.getInventory().setItem(emptySlot, container);
            updatePlayerLocations(player);
            player.sendMessage(prefix() + ChatColor.GREEN + "包含不可离包绑定物的收纳物已返还至背包。 ");
            playSound(player, "success");
            return;
        }
        Optional<BindingRecord> optional = store.find(id.get());
        if (optional.isEmpty()) {
            return;
        }
        BindingRecord record = optional.get();
        ItemStack normalized = applyBinding(item, record);
        if (!hasSpace(player)) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), normalized);
            updateDroppedItem(dropped);
            player.sendMessage(prefix() + ChatColor.YELLOW + "背包已满，物品已掉落在脚下，请尽快拾取。 ");
            playSound(player, "error");
            return;
        }
        int emptySlot = player.getInventory().firstEmpty();
        if (emptySlot < 0) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), normalized);
            updateDroppedItem(dropped);
            player.sendMessage(prefix() + ChatColor.YELLOW + "背包空间发生变化，物品已掉落在脚下。 ");
            playSound(player, "error");
            return;
        }
        player.getInventory().setItem(emptySlot, normalized);
        record.setItem(normalized.clone());
        record.setLocation(BindingLocation.player(player));
        store.markDirty(record);
        updatePlayerLocations(player);
        store.saveDirty();
        player.sendMessage(prefix() + ChatColor.GREEN + "绑定物已返还背包。 ");
        playSound(player, "success");
    }

    public Inventory getInventoryAt(BindingLocation location) {
        if (location.getType() == BindingLocation.Type.CONTAINER && location.getEntityUuid() != null) {
            return getEntityInventory(location.getEntityUuid());
        }
        Location blockLocation = location.toBlockLocation();
        if (blockLocation == null) {
            return null;
        }
        return getInventoryAt(blockLocation);
    }

    public Inventory getInventoryAt(Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return null;
        }
        World world = blockLocation.getWorld();
        world.loadChunk(blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4);
        BlockState state = world.getBlockAt(blockLocation).getState();
        if (!(state instanceof InventoryHolder holder)) {
            return null;
        }
        Inventory inventory = holder.getInventory();
        return isSafeContainerInventory(inventory) ? inventory : null;
    }

    private Inventory getEntityInventory(UUID entityUuid) {
        Entity entity = getEntityContainer(entityUuid);
        if (entity instanceof InventoryHolder holder) {
            Inventory inventory = holder.getInventory();
            return isSafeContainerInventory(inventory) ? inventory : null;
        }
        return null;
    }

    private Entity getEntityContainer(UUID entityUuid) {
        if (entityUuid == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(entityUuid);
        if (entity == null || entity instanceof Player || !entity.isValid() || !(entity instanceof InventoryHolder)) {
            return null;
        }
        return entity;
    }

    public void refreshLocationForDisplay(BindingRecord record) {
        FoundItem found = locateOne(record, true, useCoreProtectInDisplayScan());
        if (found == null
                && record.getLocation().getType() == BindingLocation.Type.DROPPED
                && isDroppedLocationVerifiedMissing(record.getLocation())) {
            record.setLocation(BindingLocation.lost());
            store.markDirty(record);
            store.saveDirty();
        }
    }

    public void refreshLocationsForDisplay(List<BindingRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<UUID, List<FoundItem>> foundById = findInteractiveOccurrencesBatch(records, useCoreProtectInDisplayScan());
        boolean dirty = false;
        for (BindingRecord record : records) {
            ItemStack beforeItem = cloneOrNull(record.getItem());
            BindingLocation beforeLocation = record.getLocation();
            FoundItem found = deduplicate(record, foundById.getOrDefault(record.getId(), List.of()), false);
            if (found == null
                && record.getLocation().getType() == BindingLocation.Type.DROPPED
                && isDroppedLocationVerifiedMissing(record.getLocation())) {
                record.setLocation(BindingLocation.lost());
                store.markDirty(record);
                dirty = true;
            }
            if (found != null && recordChanged(record, beforeItem, beforeLocation)) {
                store.markDirty(record);
                dirty = true;
            }
        }
        if (dirty) {
            store.saveDirty();
        }
    }

    public boolean isSafeContainerInventory(Inventory inventory) {
        return getInventoryLocation(inventory) != null;
    }

    public boolean isTrackableTemporaryInventory(Inventory inventory) {
        return inventory != null
                && !isPluginGuiInventory(inventory)
                && !isSafeContainerInventory(inventory)
                && inventory.getSize() > 0
                && inventory.getType() != InventoryType.CREATIVE;
    }

    public boolean isPluginGuiInventory(Inventory inventory) {
        if (inventory == null || inventory.getHolder() == null) {
            return false;
        }
        return inventory.getHolder().getClass().getName().startsWith(BinderGui.class.getName() + "$");
    }

    public boolean isTemporaryResultSlot(Inventory inventory, int rawSlot) {
        if (inventory == null || rawSlot < 0 || rawSlot >= inventory.getSize()) {
            return false;
        }
        int resultSlot = temporaryResultSlot(inventory.getType());
        return resultSlot >= 0 && rawSlot == resultSlot;
    }

    public boolean hasBoundTemporaryInput(Inventory inventory) {
        if (!isTrackableTemporaryInventory(inventory)) {
            return false;
        }
        for (int slot : temporaryInputSlots(inventory)) {
            if (slot >= 0 && slot < inventory.getSize() && containsBoundDeep(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private int[] temporaryInputSlots(Inventory inventory) {
        if (inventory == null) {
            return NO_TEMPORARY_INPUT_SLOTS;
        }
        int size = inventory.getSize();
        if (size <= 0) {
            return NO_TEMPORARY_INPUT_SLOTS;
        }
        int resultSlot = temporaryResultSlot(inventory.getType());
        if (resultSlot < 0 || resultSlot >= size) {
            int[] slots = new int[size];
            for (int slot = 0; slot < size; slot++) {
                slots[slot] = slot;
            }
            return slots;
        }
        int[] slots = new int[size - 1];
        int index = 0;
        for (int slot = 0; slot < size; slot++) {
            if (slot != resultSlot) {
                slots[index++] = slot;
            }
        }
        return index == slots.length ? slots : java.util.Arrays.copyOf(slots, index);
    }

    private int temporaryResultSlot(InventoryType type) {
        return switch (type) {
            case ANVIL, GRINDSTONE, CARTOGRAPHY, MERCHANT -> 2;
            case SMITHING, LOOM -> 3;
            case STONECUTTER -> 1;
            case CRAFTING, WORKBENCH -> 0;
            default -> -1;
        };
    }

    private String temporaryDescription(InventoryType type, Player player) {
        String name = switch (type) {
            case ANVIL -> "玩家铁砧界面";
            case GRINDSTONE -> "玩家砂轮界面";
            case CARTOGRAPHY -> "玩家制图台界面";
            case MERCHANT -> "玩家交易界面";
            case SMITHING -> "玩家锻造台界面";
            case LOOM -> "玩家织布机界面";
            case STONECUTTER -> "玩家切石机界面";
            case CRAFTING, WORKBENCH -> "玩家合成界面";
            case ENCHANTING -> "玩家附魔台界面";
            case BEACON -> "玩家信标界面";
            case ENDER_CHEST -> "玩家末影箱";
            default -> "玩家临时界面";
        };
        return name + "：" + player.getName();
    }

    private EquipmentSlot[] armorStandSlots() {
        return ARMOR_STAND_SLOTS;
    }

    private ItemStack getArmorStandItem(ArmorStand armorStand, EquipmentSlot slot) {
        EntityEquipment equipment = armorStand.getEquipment();
        return equipment == null ? null : equipment.getItem(slot);
    }

    private void setArmorStandItem(ArmorStand armorStand, EquipmentSlot slot, ItemStack item) {
        EntityEquipment equipment = armorStand.getEquipment();
        if (equipment != null) {
            equipment.setItem(slot, item);
        }
    }

    public Location getInventoryLocation(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            InventoryHolder left = doubleChest.getLeftSide();
            if (left instanceof BlockState leftState) {
                return leftState.getLocation();
            }
            return doubleChest.getLocation();
        }
        if (holder instanceof BlockInventoryHolder blockHolder) {
            return blockHolder.getBlock().getLocation();
        }
        if (holder instanceof BlockState state) {
            return state.getLocation();
        }
        if (holder instanceof Entity entity && !(entity instanceof Player)) {
            return entity.getLocation();
        }
        return null;
    }

    private Entity getInventoryContainerEntity(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Entity entity && !(entity instanceof Player)) {
            return entity;
        }
        return null;
    }

    public ItemStack applyBinding(ItemStack source, BindingRecord record) {
        ItemStack item = source.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, record.getId().toString());
        pdc.set(ownerKey, PersistentDataType.STRING, record.getOwnerUuid().toString());
        pdc.set(lockedKey, PersistentDataType.INTEGER, record.isLocked() ? 1 : 0);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(this::isBinderLoreLine);
        lore.add(message("lore.binding-title", "&6灵魂绑定"));
        lore.add(message("lore.owner", "&7绑定者：&f%owner%").replace("%owner%", record.getOwnerName()));
        lore.add(message("lore.id", "&8绑定编号：%id%").replace("%id%", shortId(record.getId())));
        lore.add(message("lore.locked", "&7不可离包：&f%locked%").replace("%locked%", record.isLocked() ? "开启" : "关闭"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack removeBinding(ItemStack source) {
        ItemStack item = source.clone();
        if (item.getAmount() != 1) {
            item.setAmount(1);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(idKey);
        pdc.remove(ownerKey);
        pdc.remove(lockedKey);
        if (meta.hasLore()) {
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.removeIf(this::isBinderLoreLine);
            meta.setLore(lore.isEmpty() ? null : lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBinderLoreLine(String line) {
        String stripped = ChatColor.stripColor(line);
        return stripped != null
                && (stripped.equals("灵魂绑定")
                || stripped.startsWith("绑定者：")
                || stripped.startsWith("绑定编号：")
                || stripped.startsWith("不可离包：")
                || stripped.startsWith("离包限制：")
                || stripped.equals(configuredBinderLore("lore.binding-title", "&6灵魂绑定"))
                || matchesConfiguredBinderLorePrefix(stripped, "lore.owner", "&7绑定者：&f%owner%")
                || matchesConfiguredBinderLorePrefix(stripped, "lore.id", "&8绑定编号：%id%")
                || matchesConfiguredBinderLorePrefix(stripped, "lore.locked", "&7不可离包：&f%locked%")
                || matchesConfiguredBinderLorePrefix(stripped, "lore.locked", "&7离包限制：&f%locked%"));
    }

    private String configuredBinderLore(String path, String fallback) {
        return ChatColor.stripColor(message(path, fallback));
    }

    private String configuredBinderLorePrefix(String path, String fallback) {
        String value = configuredBinderLore(path, fallback);
        int placeholder = value.indexOf('%');
        return placeholder >= 0 ? value.substring(0, placeholder) : value;
    }

    private boolean matchesConfiguredBinderLorePrefix(String strippedLine, String path, String fallback) {
        String prefix = configuredBinderLorePrefix(path, fallback);
        return prefix != null && !prefix.isBlank() && strippedLine.startsWith(prefix);
    }

    public Optional<UUID> getBindingId(ItemStack item) {
        if (isEmpty(item) || !item.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String value = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean isBoundItem(ItemStack item) {
        return getBindingId(item).isPresent();
    }

    public boolean isLocked(ItemStack item) {
        Optional<UUID> id = getBindingId(item);
        if (id.isPresent()) {
            Optional<BindingRecord> record = store.find(id.get());
            if (record.isPresent()) {
                return record.get().isLocked();
            }
        }
        if (isEmpty(item) || !item.hasItemMeta()) {
            return false;
        }
        Integer locked = item.getItemMeta().getPersistentDataContainer().get(lockedKey, PersistentDataType.INTEGER);
        return locked != null && locked == 1;
    }

    public boolean isOwner(ItemStack item, UUID playerId) {
        Optional<UUID> id = getBindingId(item);
        if (id.isEmpty()) {
            return false;
        }
        Optional<BindingRecord> record = store.find(id.get());
        if (record.isPresent()) {
            return record.get().getOwnerUuid().equals(playerId);
        }
        Optional<UUID> owner = getBindingOwnerId(item);
        return owner.isPresent() && owner.get().equals(playerId);
    }

    private Optional<UUID> getBindingOwnerId(ItemStack item) {
        if (isEmpty(item) || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean containsBoundDeep(ItemStack... items) {
        for (ItemStack item : items) {
            if (containsBoundDeep(item, 0)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsBoundDeep(ItemStack item, int depth) {
        if (isBoundItem(item)) {
            return true;
        }
        if (depth >= MAX_NESTED_DEPTH) {
            return false;
        }
        for (ItemStack nested : nestedContents(item)) {
            if (containsBoundDeep(nested, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsLockedDeep(ItemStack item) {
        return containsLockedDeep(item, 0);
    }

    private boolean containsLockedDeep(ItemStack item, int depth) {
        if (isLocked(item)) {
            return true;
        }
        if (depth >= MAX_NESTED_DEPTH) {
            return false;
        }
        for (ItemStack nested : nestedContents(item)) {
            if (containsLockedDeep(nested, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsLockedBindingNotOwnedBy(ItemStack item, UUID playerId) {
        return containsLockedBindingNotOwnedBy(item, playerId, 0);
    }

    private boolean containsLockedBindingNotOwnedBy(ItemStack item, UUID playerId, int depth) {
        if (isLocked(item) && !isOwner(item, playerId)) {
            return true;
        }
        if (depth >= MAX_NESTED_DEPTH) {
            return false;
        }
        for (ItemStack nested : nestedContents(item)) {
            if (containsLockedBindingNotOwnedBy(nested, playerId, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private Set<UUID> bindingIdsDeep(ItemStack item) {
        Set<UUID> ids = new HashSet<>();
        collectBindingIdsDeep(item, ids, 0);
        return ids;
    }

    public Set<UUID> bindingIdsDeepView(ItemStack item) {
        return Set.copyOf(bindingIdsDeep(item));
    }

    private void collectBindingIdsDeep(ItemStack item, Set<UUID> ids, int depth) {
        getBindingId(item).ifPresent(ids::add);
        if (depth >= MAX_NESTED_DEPTH) {
            return;
        }
        for (ItemStack nested : nestedContents(item)) {
            collectBindingIdsDeep(nested, ids, depth + 1);
        }
    }

    private List<ItemStack> nestedContents(ItemStack item) {
        if (!mayContainNestedItems(item)) {
            return List.of();
        }
        ItemMeta meta = item.getItemMeta();
        List<ItemStack> items = new ArrayList<>();
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            for (ItemStack nested : holder.getInventory().getContents()) {
                if (!isEmpty(nested)) {
                    items.add(nested);
                }
            }
        }
        if (meta instanceof BundleMeta bundleMeta) {
            for (ItemStack nested : bundleMeta.getItems()) {
                if (!isEmpty(nested)) {
                    items.add(nested);
                }
            }
        }
        return items;
    }

    private boolean mayContainNestedItems(ItemStack item) {
        if (isEmpty(item) || !item.hasItemMeta()) {
            return false;
        }
        Material material = item.getType();
        return material == Material.BUNDLE || material.name().endsWith("SHULKER_BOX");
    }

    public boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    public boolean hasSpace(Player player) {
        return player.getInventory().firstEmpty() != -1;
    }

    public int maxPerPlayer() {
        return Math.max(0, plugin.getConfig().getInt("binding.max-per-player", 27));
    }

    public boolean bindingWhitelistEnabled() {
        return plugin.getConfig().getBoolean("binding.whitelist-enabled", false);
    }

    public int bindingWhitelistSize() {
        return configuredMaterials("binding.whitelist").size();
    }

    public int bindingBlacklistSize() {
        return configuredMaterials("binding.blacklist").size();
    }

    public double lostRecallCost() {
        return Math.max(0.0D, plugin.getConfig().getDouble("recall.lost-cost", 100.0D));
    }

    public double bindCost() {
        return Math.max(0.0D, plugin.getConfig().getDouble("economy.bind-cost", 0.0D));
    }

    public double unbindCost() {
        return Math.max(0.0D, plugin.getConfig().getDouble("economy.unbind-cost", 0.0D));
    }

    public double normalRecallCost() {
        return Math.max(0.0D, plugin.getConfig().getDouble("recall.normal-cost", 0.0D));
    }

    public long recallCooldownSeconds() {
        return Math.max(0L, plugin.getConfig().getLong("recall.cooldown-seconds", 0L));
    }

    public String formatMoney(double amount) {
        return String.format(Locale.CHINA, "%.2f", amount);
    }

    public boolean playerAlertsEnabled(UUID playerId) {
        return alertPreferences.getOrDefault(playerId, true);
    }

    public boolean togglePlayerAlerts(Player player) {
        boolean enabled = !playerAlertsEnabled(player.getUniqueId());
        alertPreferences.put(player.getUniqueId(), enabled);
        saveAlertPreferences();
        player.sendMessage(prefix() + ChatColor.GREEN + "绑定物提醒已" + (enabled ? "开启" : "关闭") + "。 ");
        playSound(player, "gui.toggle");
        return enabled;
    }

    public void loadAlertPreferences() {
        alertPreferences.clear();
        File file = new File(plugin.getDataFolder(), "alerts.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = configuration.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                alertPreferences.put(UUID.fromString(key), root.getBoolean(key, true));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("提醒偏好配置中存在无效玩家 UUID：" + key + "。");
            }
        }
    }

    private void saveAlertPreferences() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，提醒偏好未保存。");
            return;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection root = configuration.createSection("players");
        for (Map.Entry<UUID, Boolean> entry : alertPreferences.entrySet()) {
            root.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            configuration.save(new File(plugin.getDataFolder(), "alerts.yml"));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存提醒偏好失败。", ex);
        }
    }

    public void alertItemLanded(Item item) {
        if (item == null || !alertEnabled("drop")) {
            return;
        }
        for (BindingRecord record : recordsFromItemDeep(item.getItemStack())) {
            alertOwner(record, "drop", ChatColor.YELLOW + "提醒：你的绑定物已落地。 "
                    + ChatColor.GRAY + "物品：" + ChatColor.WHITE + itemDisplayName(record)
                    + ChatColor.GRAY + "，位置：" + ChatColor.WHITE + formatLocation(item.getLocation()) + "。", "alert.drop");
        }
    }

    public void alertItemPickedUp(ItemStack item, Player picker) {
        if (picker == null || !alertEnabled("pickup")) {
            return;
        }
        for (BindingRecord record : recordsFromItemDeep(item)) {
            if (record.getOwnerUuid().equals(picker.getUniqueId())) {
                continue;
            }
            alertOwner(record, "pickup:" + picker.getUniqueId(), ChatColor.YELLOW + "提醒：你的绑定物被玩家捡起。 "
                    + ChatColor.GRAY + "玩家：" + ChatColor.WHITE + picker.getName()
                    + ChatColor.GRAY + "，物品：" + ChatColor.WHITE + itemDisplayName(record)
                    + ChatColor.GRAY + "，位置：" + ChatColor.WHITE + formatLocation(picker.getLocation()) + "。", "alert.pickup");
        }
    }

    public void alertOperatedByOther(Player actor, String action, ItemStack... items) {
        if (actor == null || !alertEnabled("operation")) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (ItemStack item : items) {
            for (BindingRecord record : recordsFromItemDeep(item)) {
                if (record.getOwnerUuid().equals(actor.getUniqueId()) || !seen.add(record.getId())) {
                    continue;
                }
                alertOwner(record, "operation:" + actor.getUniqueId(), ChatColor.YELLOW + "提醒：你的绑定物正被其他玩家操作。 "
                        + ChatColor.GRAY + "玩家：" + ChatColor.WHITE + actor.getName()
                        + ChatColor.GRAY + "，操作：" + ChatColor.WHITE + action
                        + ChatColor.GRAY + "，物品：" + ChatColor.WHITE + itemDisplayName(record) + "。", "alert.operation");
            }
        }
    }

    private List<BindingRecord> recordsFromItemDeep(ItemStack item) {
        List<BindingRecord> records = new ArrayList<>();
        for (UUID id : bindingIdsDeep(item)) {
            store.find(id).ifPresent(records::add);
        }
        return records;
    }

    private boolean alertEnabled(String type) {
        return plugin.getConfig().getBoolean("alerts.enabled", true)
                && plugin.getConfig().getBoolean("alerts." + type, true);
    }

    private void alertOwner(BindingRecord record, String key, String message, String sound) {
        Player owner = Bukkit.getPlayer(record.getOwnerUuid());
        if (owner == null || !playerAlertsEnabled(owner.getUniqueId()) || !checkAlertCooldown(record.getId() + ":" + key)) {
            return;
        }
        owner.sendMessage(prefix() + message);
        playSound(owner, sound);
    }

    private boolean checkAlertCooldown(String key) {
        long seconds = Math.max(0L, plugin.getConfig().getLong("alerts.cooldown-seconds", 5L));
        if (seconds <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        long until = alertCooldowns.getOrDefault(key, 0L);
        if (until > now) {
            return false;
        }
        alertCooldowns.put(key, now + seconds * 1000L);
        return true;
    }

    private String itemDisplayName(BindingRecord record) {
        ItemStack item = record.getItem();
        if (!isEmpty(item) && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(item.getItemMeta().getDisplayName()) + "（" + shortId(record.getId()) + "）";
        }
        return (isEmpty(item) ? "未知物品" : item.getType().name()) + "（" + shortId(record.getId()) + "）";
    }

    private boolean canBindMaterial(Player player, Material material) {
        if (configuredMaterials("binding.blacklist").contains(material)) {
            player.sendMessage(prefix() + ChatColor.RED + "该物品类型已被配置为不可绑定：" + material.name() + "。 ");
            playSound(player, "error");
            return false;
        }
        if (bindingWhitelistEnabled() && !configuredMaterials("binding.whitelist").contains(material)) {
            player.sendMessage(prefix() + ChatColor.RED + "绑定白名单已开启，该物品类型不在白名单中：" + material.name() + "。 ");
            playSound(player, "error");
            return false;
        }
        return true;
    }

    private Set<Material> configuredMaterials(String path) {
        Set<Material> cached = materialCache.get(path);
        if (cached != null) {
            return cached;
        }
        Set<Material> materials = new HashSet<>();
        for (String value : plugin.getConfig().getStringList(path)) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(value.trim());
            if (material != null) {
                materials.add(material);
            }
        }
        Set<Material> cachedMaterials = Set.copyOf(materials);
        materialCache.put(path, cachedMaterials);
        return cachedMaterials;
    }

    public void playSound(Player player, String key) {
        if (player == null || !plugin.getConfig().getBoolean("sounds.enabled", true)) {
            return;
        }
        String path = "sounds." + key;
        String soundName = plugin.getConfig().getString(path + ".sound", defaultSoundName(key));
        if (soundName == null || soundName.isBlank() || soundName.equalsIgnoreCase("NONE")) {
            return;
        }
        float volume = (float) plugin.getConfig().getDouble(path + ".volume", defaultSoundVolume(key));
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", defaultSoundPitch(key));
        try {
            Sound sound = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(soundName.toLowerCase(Locale.ROOT)));
            if (sound == null) {
                throw new IllegalArgumentException("未知音效");
            }
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("音效配置无效：sounds." + key + ".sound = " + soundName + "。 ");
        }
    }

    private void playSenderSound(CommandSender sender, String key) {
        if (sender instanceof Player player) {
            playSound(player, key);
        }
    }

    private String defaultSoundName(String key) {
        return switch (key) {
            case "gui.open" -> "BLOCK_CHEST_OPEN";
            case "gui.click" -> "UI_BUTTON_CLICK";
            case "gui.page" -> "ITEM_BOOK_PAGE_TURN";
            case "gui.back" -> "BLOCK_CHEST_CLOSE";
            case "gui.confirm-open" -> "BLOCK_NOTE_BLOCK_PLING";
            case "gui.confirm" -> "ENTITY_EXPERIENCE_ORB_PICKUP";
            case "gui.cancel" -> "BLOCK_CHEST_CLOSE";
            case "gui.detail" -> "BLOCK_NOTE_BLOCK_HAT";
            case "gui.admin" -> "BLOCK_NOTE_BLOCK_BASS";
            case "gui.bind" -> "BLOCK_ENCHANTMENT_TABLE_USE";
            case "gui.recall-select" -> "ENTITY_ENDER_PEARL_THROW";
            case "gui.toggle" -> "BLOCK_LEVER_CLICK";
            case "gui.refresh" -> "BLOCK_NOTE_BLOCK_BIT";
            case "gui.reload" -> "BLOCK_NOTE_BLOCK_CHIME";
            case "gui.owner-select" -> "ENTITY_PLAYER_ATTACK_NODAMAGE";
            case "gui.stats" -> "BLOCK_NOTE_BLOCK_XYLOPHONE";
            case "gui.backup" -> "BLOCK_NOTE_BLOCK_CHIME";
            case "gui.scan" -> "BLOCK_BEACON_ACTIVATE";
            case "alert.drop" -> "ENTITY_ITEM_PICKUP";
            case "alert.pickup" -> "BLOCK_NOTE_BLOCK_PLING";
            case "alert.operation" -> "BLOCK_NOTE_BLOCK_BELL";
            case "gui.disabled" -> "BLOCK_NOTE_BLOCK_DIDGERIDOO";
            case "success" -> "ENTITY_PLAYER_LEVELUP";
            case "error" -> "ENTITY_VILLAGER_NO";
            default -> "UI_BUTTON_CLICK";
        };
    }

    private double defaultSoundVolume(String key) {
        return switch (key) {
            case "success" -> 0.7D;
            case "error" -> 0.8D;
            case "gui.disabled" -> 0.5D;
            default -> 0.6D;
        };
    }

    private double defaultSoundPitch(String key) {
        return switch (key) {
            case "gui.page" -> 1.25D;
            case "gui.confirm-open" -> 1.35D;
            case "gui.confirm", "gui.toggle", "gui.refresh", "gui.reload", "gui.backup", "success" -> 1.2D;
            case "gui.scan" -> 1.1D;
            case "alert.pickup", "alert.operation" -> 1.3D;
            case "gui.cancel", "gui.back", "gui.admin", "gui.disabled", "error" -> 0.75D;
            default -> 1.0D;
        };
    }

    private boolean withdraw(Player player, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        if (economy == null) {
            player.sendMessage(prefix() + ChatColor.RED + "未检测到 Vault 经济服务，无法执行付费操作。 ");
            playSound(player, "error");
            return false;
        }
        if (!economy.has(player, amount)) {
            player.sendMessage(prefix() + ChatColor.RED + "余额不足，本次操作需要：" + formatMoney(amount) + "。 ");
            playSound(player, "error");
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            player.sendMessage(prefix() + ChatColor.RED + "扣费失败：" + response.errorMessage + "。 ");
            playSound(player, "error");
            return false;
        }
        return true;
    }

    private void refund(Player player, double amount, String reason) {
        if (amount <= 0.0D || economy == null) {
            return;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            player.sendMessage(prefix() + ChatColor.YELLOW + "由于" + reason + "，已退款：" + formatMoney(amount) + "。 ");
        } else {
            plugin.getLogger().warning("退款失败：" + player.getName() + "，金额 " + formatMoney(amount) + "，原因：" + response.errorMessage + "。");
        }
    }

    private boolean checkRecallCooldown(Player player) {
        long until = recallCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long now = System.currentTimeMillis();
        if (until <= now) {
            recallCooldowns.remove(player.getUniqueId());
            return true;
        }
        long seconds = Math.max(1L, (until - now + 999L) / 1000L);
        player.sendMessage(prefix() + ChatColor.RED + "召回冷却中，请 " + seconds + " 秒后再试。 ");
        playSound(player, "error");
        return false;
    }

    private void startRecallCooldown(Player player) {
        long seconds = recallCooldownSeconds();
        if (seconds <= 0L) {
            return;
        }
        recallCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    public void sendRecordInfo(CommandSender sender, BindingRecord record) {
        if (!requireOwnerOrAdmin(sender, record)) {
            return;
        }
        FoundItem found = locateOne(record, true, true);
        sender.sendMessage(prefix() + ChatColor.YELLOW + "绑定物详情：");
        sender.sendMessage(ChatColor.GRAY + "列表编号：" + ChatColor.WHITE + store.ownerIndex(record));
        sender.sendMessage(ChatColor.GRAY + "绑定编号：" + ChatColor.WHITE + record.getId());
        sender.sendMessage(ChatColor.GRAY + "绑定者：" + ChatColor.WHITE + record.getOwnerName());
        sender.sendMessage(ChatColor.GRAY + "物品类型：" + ChatColor.WHITE + (isEmpty(record.getItem()) ? "快照缺失" : record.getItem().getType().name()));
        sender.sendMessage(ChatColor.GRAY + "不可离包：" + ChatColor.WHITE + (record.isLocked() ? "开启" : "关闭"));
        sender.sendMessage(ChatColor.GRAY + "记录位置：" + ChatColor.WHITE + record.getLocation().describe());
        sender.sendMessage(ChatColor.GRAY + "扫描结果：" + ChatColor.WHITE + (found == null ? "未找到真实物品" : found.describe()));
    }

    public void scanForAdmin(CommandSender sender, BindingRecord record) {
        if (!requireAdmin(sender)) {
            return;
        }
        scanRecordLocation(sender, record);
    }

    public void scanForOwnerOrAdmin(CommandSender sender, BindingRecord record) {
        if (!requireOwnerOrAdmin(sender, record)) {
            return;
        }
        scanRecordLocation(sender, record);
    }

    private void scanRecordLocation(CommandSender sender, BindingRecord record) {
        FoundItem found = locateOne(record, true, true);
        if (found == null) {
            sender.sendMessage(prefix() + ChatColor.RED + "扫描完成：未找到物品；记录位置：" + record.getLocation().describe() + "。 ");
            playSenderSound(sender, "error");
            return;
        }
        sender.sendMessage(prefix() + ChatColor.GREEN + "扫描完成：已刷新真实位置。 ");
        sender.sendMessage(ChatColor.GRAY + "真实位置：" + ChatColor.WHITE + found.describe());
        playSenderSound(sender, "success");
    }

    public boolean createAutomaticBackup(String trigger, boolean includeOptionalFiles) {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            return false;
        }
        return createBackup(null, trigger, includeOptionalFiles, false);
    }

    public boolean createManualBackup(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return false;
        }
        return createBackup(sender, "手动备份", true, true);
    }

    public boolean createPreRestoreBackup(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return false;
        }
        return createBackup(sender, "还原前备份", true, false);
    }

    private boolean createBackup(CommandSender sender, String trigger, boolean includeOptionalFiles, boolean notifySender) {
        store.save();
        if (!store.checkpoint()) {
            if (notifySender && sender != null) {
                sender.sendMessage(prefix() + ChatColor.RED + "备份失败：数据库检查点未完成，请查看控制台。 ");
                playSenderSound(sender, "error");
            } else {
                plugin.getLogger().warning("自动备份失败：绑定数据库无法完成安全检查点。触发原因：" + trigger + "。");
            }
            return false;
        }
        File backupRoot = backupRootFolder();
        if (!backupRoot.exists() && !backupRoot.mkdirs()) {
            if (notifySender && sender != null) {
                sender.sendMessage(prefix() + ChatColor.RED + "无法创建备份目录，请检查文件权限。 ");
                playSenderSound(sender, "error");
            } else {
                plugin.getLogger().warning("自动备份失败：无法创建备份目录。触发原因：" + trigger + "。");
            }
            return false;
        }

        LocalDateTime createdAt = LocalDateTime.now();
        String baseStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(createdAt) + "-" + safeBackupTag(trigger);
        File databaseFile = store.getDatabaseFile();
        if (!databaseFile.exists() || !databaseFile.isFile()) {
            if (notifySender && sender != null) {
                sender.sendMessage(prefix() + ChatColor.RED + "备份失败：数据库文件不存在，请检查数据库状态。 ");
                playSenderSound(sender, "error");
            } else {
                plugin.getLogger().warning("自动备份失败：绑定数据库文件不存在。触发原因：" + trigger + "。");
            }
            return false;
        }
        long databaseActualTime = databaseFile.lastModified();
        File backupFolder = createUniqueBackupFolder(backupRoot, baseStamp);
        if (backupFolder == null) {
            if (notifySender && sender != null) {
                sender.sendMessage(prefix() + ChatColor.RED + "无法创建备份文件夹，请检查文件权限。 ");
                playSenderSound(sender, "error");
            } else {
                plugin.getLogger().warning("自动备份失败：无法创建本次备份文件夹。触发原因：" + trigger + "。");
            }
            return false;
        }
        String stamp = backupFolder.getName();
        List<String> copied = new ArrayList<>();
        File databaseBackup = new File(backupFolder, "binder.db");
        if (!copyBackupFile(databaseFile, databaseBackup, "绑定数据库", copied)) {
            if (notifySender && sender != null) {
                sender.sendMessage(prefix() + ChatColor.RED + "备份失败：数据库复制失败，请查看控制台。 ");
                playSenderSound(sender, "error");
            } else {
                plugin.getLogger().warning("自动备份失败：绑定数据库无法复制。触发原因：" + trigger + "。");
            }
            return false;
        }
        boolean optionalOk = true;
        if (includeOptionalFiles) {
            optionalOk &= copyBackupFile(new File(plugin.getDataFolder(), "data.yml"), new File(backupFolder, "data-legacy.yml"), "旧版绑定数据", copied);
            optionalOk &= copyBackupFile(new File(plugin.getDataFolder(), "quarantine.yml"), new File(backupFolder, "quarantine.yml"), "隔离记录", copied);
            optionalOk &= copyBackupFile(new File(plugin.getDataFolder(), "config.yml"), new File(backupFolder, "config.yml"), "主配置", copied);
            optionalOk &= copyBackupFile(new File(plugin.getDataFolder(), "messages.yml"), new File(backupFolder, "messages.yml"), "消息配置", copied);
            optionalOk &= copyBackupFile(new File(plugin.getDataFolder(), "alerts.yml"), new File(backupFolder, "alerts.yml"), "提醒偏好", copied);
        }
        writeBackupInfo(backupFolder, databaseBackup, trigger, createdAt, databaseActualTime, copied, includeOptionalFiles);
        pruneBackups(backupRoot);

        if (notifySender && sender != null) {
            sender.sendMessage(prefix() + ChatColor.GREEN + "手动备份完成，标识：" + stamp + "。 ");
            sender.sendMessage(ChatColor.GRAY + "备份文件夹：" + ChatColor.WHITE + backupFolder.getName());
            sender.sendMessage(ChatColor.GRAY + "已备份：" + ChatColor.WHITE + String.join("、", copied));
        } else {
            plugin.getLogger().info("自动备份已完成：触发原因 " + trigger + "，备份文件夹 " + backupFolder.getName() + "，内容：" + String.join("、", copied) + "。");
        }
        if (!optionalOk) {
            if (notifySender && sender != null) {
                sender.sendMessage(prefix() + ChatColor.YELLOW + "部分可选文件备份失败，核心数据已备份。 ");
            } else {
                plugin.getLogger().warning("自动备份时部分可选文件备份失败，核心绑定数据库已完成。触发原因：" + trigger + "。");
            }
        }
        if (notifySender && sender != null) {
            playSenderSound(sender, "success");
        }
        return true;
    }

    private File createUniqueBackupFolder(File backupRoot, String baseName) {
        for (int index = 0; index < 1000; index++) {
            String name = index == 0 ? baseName : baseName + "-" + (index + 1);
            File folder = new File(backupRoot, name);
            if (folder.exists()) {
                continue;
            }
            if (folder.mkdir()) {
                return folder;
            }
            return null;
        }
        plugin.getLogger().warning("自动备份失败：同一秒内备份文件夹数量过多，无法生成唯一文件夹名。");
        return null;
    }

    private boolean copyBackupFile(File source, File target, String label, List<String> copied) {
        if (!source.exists()) {
            return true;
        }
        try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            copied.add(label);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "备份文件失败：" + label + "，来源：" + source.getName() + "。", ex);
            return false;
        }
    }

    private File backupRootFolder() {
        return new File(plugin.getDataFolder(), "Backup");
    }

    private void writeBackupInfo(File backupFolder, File databaseBackup, String trigger, LocalDateTime createdAt, long databaseActualTime, List<String> copied, boolean includeOptionalFiles) {
        YamlConfiguration info = new YamlConfiguration();
        info.set("说明", "Binder 数据库备份信息，请勿在服务器运行时手动修改备份文件。");
        info.set("触发原因", trigger);
        info.set("备份时间", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(createdAt));
        info.set("备份时间戳", createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        info.set("数据库实际时间", formatFileTime(databaseActualTime));
        info.set("数据库实际时间戳", databaseActualTime);
        info.set("数据库文件", databaseBackup.getName());
        info.set("数据库大小", databaseBackup.exists() ? databaseBackup.length() : 0L);
        info.set("包含可选文件", includeOptionalFiles);
        info.set("已备份内容", copied);
        try {
            info.save(new File(backupFolder, "备份信息.yml"));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "写入备份信息文件失败：" + backupFolder.getName() + "。", ex);
        }
    }

    private String formatFileTime(long timestamp) {
        if (timestamp <= 0L) {
            return "未知";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()));
    }

    private void requestEventBackup(String reason) {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)
                || !plugin.getConfig().getBoolean("backup.event.enabled", true)) {
            return;
        }
        queuedEventBackupReason = reason;
        if (eventBackupQueued) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldownMillis = Math.max(0L, plugin.getConfig().getLong("backup.event.cooldown-seconds", 300L)) * 1000L;
        long delayMillis = Math.max(0L, lastEventBackupAt + cooldownMillis - now);
        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        eventBackupQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            eventBackupQueued = false;
            String trigger = queuedEventBackupReason == null ? "事件触发" : queuedEventBackupReason;
            queuedEventBackupReason = null;
            lastEventBackupAt = System.currentTimeMillis();
            createAutomaticBackup("事件-" + trigger, plugin.getConfig().getBoolean("backup.event.include-optional-files", false));
        }, delayTicks);
    }

    private void pruneBackups(File backupRoot) {
        int keep = Math.max(0, plugin.getConfig().getInt("backup.keep-latest", 48));
        if (keep <= 0 || !backupRoot.exists()) {
            return;
        }
        File[] folders = backupRoot.listFiles(file -> file.isDirectory() && new File(file, "binder.db").exists());
        if (folders == null || folders.length <= keep) {
            return;
        }
        List<File> backups = new ArrayList<>(List.of(folders));
        backups.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i = keep; i < backups.size(); i++) {
            File folder = backups.get(i);
            if (!deleteBackupFolder(folder)) {
                plugin.getLogger().warning("自动清理旧数据库备份文件夹失败：" + folder.getName() + "。");
            }
        }
    }

    private boolean deleteBackupFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (!deleteBackupFolder(file)) {
                        return false;
                    }
                } else if (!file.delete()) {
                    return false;
                }
            }
        }
        return folder.delete();
    }

    private String safeBackupTag(String trigger) {
        String value = trigger == null || trigger.isBlank() ? "backup" : trigger;
        String sanitized = value.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5_-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.length() > 24) {
            sanitized = sanitized.substring(0, 24);
        }
        return sanitized.isBlank() ? "backup" : sanitized;
    }

    public void showQuarantineList(CommandSender sender, int page) {
        if (!requireAdmin(sender)) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "quarantine.yml");
        if (!file.exists()) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "隔离仓库暂无记录。 ");
            return;
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = configuration.getConfigurationSection("items");
        if (root == null || root.getKeys(false).isEmpty()) {
            sender.sendMessage(prefix() + ChatColor.YELLOW + "隔离仓库暂无记录。 ");
            return;
        }
        List<ConfigurationSection> entries = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section != null) {
                entries.add(section);
            }
        }
        entries.sort(Comparator.comparingLong(section -> -section.getLong("created-at", 0L)));
        int pageSize = 8;
        int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int safePage = Math.max(1, Math.min(page, totalPages));
        int start = (safePage - 1) * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        sender.sendMessage(prefix() + ChatColor.YELLOW + "重复物隔离仓库 · 第 " + safePage + "/" + totalPages + " 页，共 " + entries.size() + " 条：");
        for (int i = start; i < end; i++) {
            ConfigurationSection section = entries.get(i);
            ItemStack item = section.getItemStack("item");
            String type = item == null ? "未知物品" : item.getType().name();
            sender.sendMessage(ChatColor.GOLD + String.valueOf(i + 1) + ChatColor.GRAY
                    + ". " + type
                    + "，绑定编号：" + section.getString("binding-id", "未知")
                    + "，发现位置：" + section.getString("duplicate-location", "未知"));
        }
        sender.sendMessage(ChatColor.GRAY + "隔离物仅保存在 quarantine.yml 中，不会自动发放，避免再次产生复制风险。");
    }

    public String statusLine(BindingRecord record) {
        BindingLocation location = record.getLocation();
        if (location.getType() == BindingLocation.Type.LOST) {
            return ChatColor.RED + "状态：✘ 已丢失，可付费召回";
        }
        if (location.getType() == BindingLocation.Type.PLAYER && location.getHolderUuid() != null && !location.getHolderUuid().equals(record.getOwnerUuid())) {
            return ChatColor.YELLOW + "状态：⚠ 由其他玩家持有（" + location.getHolderName() + "）";
        }
        if (location.getType() == BindingLocation.Type.TEMPORARY && location.getHolderUuid() != null && !location.getHolderUuid().equals(record.getOwnerUuid())) {
            return ChatColor.YELLOW + "状态：⚠ " + location.describe();
        }
        return ChatColor.GREEN + "状态：✔ " + location.describe();
    }

    public String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "未知位置";
        }
        return location.getWorld().getName() + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    public boolean isUnsafeUse(Material material) {
        String name = material.name();
        return material.isBlock()
                || material.isEdible()
                || name.endsWith("_BUCKET")
                || name.endsWith("_BOAT")
                || name.endsWith("_CHEST_BOAT")
                || name.endsWith("_MINECART")
                || name.endsWith("_SPAWN_EGG")
                || name.equals("MINECART")
                || material == Material.POTION
                || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION
                || material == Material.MILK_BUCKET
                || material == Material.EXPERIENCE_BOTTLE
                || material == Material.ENDER_PEARL
                || material == Material.ENDER_EYE
                || material == Material.EGG
                || material == Material.SNOWBALL
                || material == Material.TRIDENT
                || material == Material.FIREWORK_ROCKET
                || material == Material.FIRE_CHARGE
                || material == Material.BONE_MEAL
                || material == Material.GLASS_BOTTLE
                || material == Material.BOWL
                || material == Material.LEAD
                || material == Material.ITEM_FRAME
                || material == Material.GLOW_ITEM_FRAME
                || material == Material.PAINTING
                || material == Material.ARMOR_STAND
                || material == Material.END_CRYSTAL;
    }

    private boolean hasKnownPlayerLikeLocation(BindingRecord record) {
        BindingLocation location = record.getLocation();
        return (location.getType() == BindingLocation.Type.PLAYER || location.getType() == BindingLocation.Type.TEMPORARY)
                && location.getHolderUuid() != null;
    }

    private boolean canCreateLostReplacement(BindingRecord record) {
        if (coreProtectHook != null && coreProtectHook.isAvailable()) {
            if (!coreProtectHook.hasFreshLookup(record)
                    || coreProtectHook.isLookupPending(record)
                    || coreProtectHook.hasCachedCandidates(record)) {
                plugin.getLogger().warning("已暂缓丢失补发：绑定编号 " + shortId(record.getId())
                        + " 的 CoreProtect 候选仍在异步查询或验证中。");
                return false;
            }
        }
        BindingLocation location = record.getLocation();
        if (location.getType() == BindingLocation.Type.LOST) {
            return true;
        }
        if (location.getType() == BindingLocation.Type.DROPPED) {
            return isDroppedLocationVerifiedMissing(location);
        }
        plugin.getLogger().warning("已拒绝生成丢失召回以避免复制风险：绑定编号 " + shortId(record.getId())
                + "，最后记录位置：" + location.describe() + "。");
        return false;
    }

    private boolean isHeldByOtherPlayer(FoundItem found, UUID ownerUuid) {
        return found.getPlayer() != null && !found.getPlayer().getUniqueId().equals(ownerUuid);
    }

    private boolean useCoreProtectInFullScan() {
        return plugin.getConfig().getBoolean("coreprotect.enabled", true)
                && plugin.getConfig().getBoolean("coreprotect.use-in-auto-scan", true);
    }

    private boolean useCoreProtectInDisplayScan() {
        return plugin.getConfig().getBoolean("coreprotect.enabled", true)
                && plugin.getConfig().getBoolean("coreprotect.use-in-display-scan", true);
    }

    private void debugCoreProtectLog(String message) {
        if (plugin.getConfig().getBoolean("coreprotect.debug-logging", false)) {
            plugin.getLogger().info(message);
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender instanceof Player player && !player.hasPermission("bind.admin")) {
            player.sendMessage(prefix() + ChatColor.RED + "你没有管理员权限。 ");
            playSound(player, "error");
            return false;
        }
        return true;
    }

    private boolean requireOwner(CommandSender sender, BindingRecord record) {
        if (sender instanceof Player player && !record.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(prefix() + ChatColor.RED + "你只能操作自己的绑定物。 ");
            playSound(player, "error");
            return false;
        }
        return true;
    }

    private boolean requireOwnerOrAdmin(CommandSender sender, BindingRecord record) {
        if (sender instanceof Player player
                && !record.getOwnerUuid().equals(player.getUniqueId())
                && !player.hasPermission("bind.admin")) {
            player.sendMessage(prefix() + ChatColor.RED + "你只能操作自己的绑定物。 ");
            playSound(player, "error");
            return false;
        }
        return true;
    }

    private void logContainerRemoval(FoundItem found, String user) {
        if (found.getSource() == FoundItem.Source.CONTAINER && found.getContainerEntity() == null) {
            logContainerTransaction(user, found.getContainerLocation());
        }
    }

    private void logContainerTransaction(String user, Location location) {
        if (coreProtectHook != null) {
            coreProtectHook.logContainerTransaction(user, location);
        }
    }
}




