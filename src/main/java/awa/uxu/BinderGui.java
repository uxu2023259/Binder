package awa.uxu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BinderGui {
    private static final int PAGE_SIZE = 45;
    private final BinderPlugin plugin;
    private final BindingService service;
    private final BindingStore store;
    private final Map<UUID, Long> confirmDebounce = new HashMap<>();

    public BinderGui(BinderPlugin plugin, BindingService service, BindingStore store) {
        this.plugin = plugin;
        this.service = service;
        this.store = store;
    }

    public void openMain(Player player) {
        MainHolder holder = new MainHolder();
        Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.main-title", "&5灵魂绑定 · 主菜单"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.PURPLE_STAINED_GLASS_PANE);
        inventory.setItem(4, named(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "灵魂绑定", List.of(
                ChatColor.GRAY + "先绑定，再从列表召回或管理。",
                ChatColor.GRAY + "当前绑定：" + ChatColor.WHITE + store.byOwner(player.getUniqueId()).size() + "/" + service.maxPerPlayer()
        )));
        inventory.setItem(11, named(Material.ENCHANTED_BOOK, ChatColor.GREEN + "绑定手中物品", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "绑定主手物品",
                ChatColor.GRAY + "要求数量必须为 1。",
                ChatColor.GRAY + "绑定费用：" + ChatColor.WHITE + service.formatMoney(service.bindCost()),
                ChatColor.GRAY + "当前数量：" + ChatColor.WHITE + store.byOwner(player.getUniqueId()).size() + "/" + service.maxPerPlayer()
        )));
        inventory.setItem(13, named(Material.CHEST, ChatColor.AQUA + "打开绑定物列表", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "查看全部绑定物",
                ChatColor.GRAY + "列表中可直接召回、查看详情。",
                ChatColor.GRAY + "找不到物品时再点刷新。"
        )));
        boolean alerts = service.playerAlertsEnabled(player.getUniqueId());
        inventory.setItem(15, named(alerts ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.YELLOW + "提醒：" + (alerts ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"),
                List.of(
                        ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "开启或关闭提醒",
                        ChatColor.GRAY + "提醒落地、拾取和他人操作。",
                        ChatColor.GRAY + "冷却：" + ChatColor.WHITE + plugin.getConfig().getLong("alerts.cooldown-seconds", 5L) + " 秒"
                )));
        inventory.setItem(29, named(Material.SPYGLASS, ChatColor.AQUA + "安全体检", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "查看异常和建议",
                ChatColor.GRAY + "适合找不到物品时使用。"
        )));
        inventory.setItem(31, named(Material.EMERALD, ChatColor.GREEN + "费用与规则", mainInfoLore(player)));
        inventory.setItem(33, named(Material.BOOK, ChatColor.GOLD + "帮助", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "查看三步说明",
                ChatColor.GRAY + "绑定、查看、召回。"
        )));
        if (player.hasPermission("bind.admin")) {
            inventory.setItem(40, named(Material.COMMAND_BLOCK, ChatColor.RED + "管理员控制台", List.of(
                    ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "打开管理功能",
                    ChatColor.GRAY + "全服绑定：" + ChatColor.WHITE + store.all().size()
            )));
        }
        player.openInventory(inventory);
        service.playSound(player, "gui.open");
    }

    private void openHelp(Player player) {
        HelpHolder holder = new HelpHolder();
        Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.help-title", "&5✦ 灵魂绑定 · 新手指南"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.PURPLE_STAINED_GLASS_PANE);
        inventory.setItem(4, named(Material.BOOK, ChatColor.GOLD + "三步用好灵魂绑定", List.of(
                ChatColor.GRAY + "绑定 → 查看 → 召回。",
                ChatColor.GRAY + "不确定位置时再点刷新。"
        )));
        inventory.setItem(11, named(Material.ENCHANTED_BOOK, ChatColor.GREEN + "1. 绑定主手物品", List.of(
                ChatColor.GRAY + "把要保护的物品拿在主手。",
                ChatColor.GRAY + "数量必须为 1。"
        )));
        inventory.setItem(13, named(Material.CHEST, ChatColor.AQUA + "2. 查看列表", List.of(
                ChatColor.YELLOW + "左键：" + ChatColor.WHITE + "安全召回",
                ChatColor.YELLOW + "右键：" + ChatColor.WHITE + "查看详情"
        )));
        inventory.setItem(15, named(Material.ENDER_PEARL, ChatColor.LIGHT_PURPLE + "3. 安全召回", List.of(
                ChatColor.GRAY + "召回会先定位并移除真实原物品。",
                ChatColor.GRAY + "原物品移动时会失败，不会生成副本。"
        )));
        inventory.setItem(29, named(Material.REDSTONE_TORCH, ChatColor.YELLOW + "不可离包", List.of(
                ChatColor.GRAY + "开启后会阻止丢弃、死亡掉落、放入危险位置等行为。",
                ChatColor.GRAY + "可在详情页随时切换。"
        )));
        inventory.setItem(31, named(Material.SPYGLASS, ChatColor.AQUA + "找不到物品", List.of(
                ChatColor.GRAY + "先在列表或详情页点击刷新。",
                ChatColor.GRAY + "如果显示已丢失，可按确认页提示付费召回。",
                ChatColor.GRAY + "如果提示临时界面同步中，请稍等几秒再试。"
        )));
        inventory.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "← 返回主菜单", List.of(ChatColor.GRAY + "回到 /bind 主菜单。")));
        player.openInventory(inventory);
        service.playSound(player, "gui.open");
    }

    private void openPlayerStats(Player player) {
        List<BindingRecord> records = store.byOwner(player.getUniqueId());
        StatisticSnapshot stats = statistics(records, 0);
        int unlocked = Math.max(0, stats.total() - stats.locked());
        PlayerStatsHolder holder = new PlayerStatsHolder();
        Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.player-stats-title", "&5✦ 我的绑定体检"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.PURPLE_STAINED_GLASS_PANE);
        inventory.setItem(4, named(Material.SPYGLASS, ChatColor.LIGHT_PURPLE + "绑定体检", List.of(
                ChatColor.GRAY + "绑定数量：" + ChatColor.WHITE + stats.total() + "/" + service.maxPerPlayer(),
                ChatColor.GRAY + "提醒状态：" + ChatColor.WHITE + (service.playerAlertsEnabled(player.getUniqueId()) ? "开启" : "关闭"),
                ChatColor.GRAY + "显示来源：" + ChatColor.WHITE + "记录位置"
        )));
        inventory.setItem(11, named(Material.CHEST, ChatColor.GREEN + "打开绑定物列表", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "查看、召回和管理",
                ChatColor.GRAY + "当前绑定：" + ChatColor.WHITE + stats.total() + "/" + service.maxPerPlayer()
        )));
        inventory.setItem(13, named(stats.lost() > 0 || stats.dropped() > 0 ? Material.BELL : Material.TOTEM_OF_UNDYING,
                ChatColor.YELLOW + "当前建议", playerAdviceLore(stats, unlocked)));
        inventory.setItem(15, named(Material.COMPASS, ChatColor.AQUA + "扫描刷新", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "扫描你的全部绑定物",
                ChatColor.GRAY + "只刷新位置，不移动物品。"
        )));
        inventory.setItem(29, named(Material.SHIELD, ChatColor.GREEN + "保护状态", List.of(
                ChatColor.GRAY + "不可离包：" + ChatColor.WHITE + stats.locked(),
                ChatColor.GRAY + "可离开背包：" + ChatColor.WHITE + unlocked,
                ChatColor.GRAY + "已丢失：" + ChatColor.WHITE + stats.lost(),
                ChatColor.GRAY + "掉落物：" + ChatColor.WHITE + stats.dropped()
        )));
        boolean alerts = service.playerAlertsEnabled(player.getUniqueId());
        inventory.setItem(31, named(alerts ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.YELLOW + "提醒：" + (alerts ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"),
                List.of(ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "切换你的绑定物提醒。")));
        inventory.setItem(33, named(Material.EMERALD, ChatColor.GOLD + "费用", List.of(
                ChatColor.GRAY + "绑定：" + ChatColor.WHITE + service.formatMoney(service.bindCost()),
                ChatColor.GRAY + "普通召回：" + ChatColor.WHITE + service.formatMoney(service.normalRecallCost()),
                ChatColor.GRAY + "丢失召回：" + ChatColor.WHITE + service.formatMoney(service.lostRecallCost())
        )));
        BindingRecord urgent = firstAttentionRecord(records);
        if (urgent != null) {
            inventory.setItem(20, named(Material.RECOVERY_COMPASS, ChatColor.RED + "处理异常绑定物", List.of(
                    ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "打开最需要处理的绑定物",
                    ChatColor.GRAY + "绑定序号：" + ChatColor.WHITE + "#" + store.ownerIndex(urgent),
                    ChatColor.GRAY + "原因：" + ChatColor.WHITE + ChatColor.stripColor(problemReason(urgent)),
                    ChatColor.GRAY + "位置：" + ChatColor.WHITE + urgent.getLocation().describe()
            )));
        }
        inventory.setItem(22, named(unlocked > 0 ? Material.REDSTONE_TORCH : Material.SHIELD,
                unlocked > 0 ? ChatColor.YELLOW + "一键开启不可离包" : ChatColor.GREEN + "不可离包状态良好",
                unlocked > 0 ? List.of(
                        ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "保护所有未锁定绑定物",
                        ChatColor.GRAY + "当前未锁定：" + ChatColor.WHITE + unlocked + " 件"
                ) : List.of(
                        ChatColor.GRAY + "你的绑定物都已开启不可离包。"
                )));
        inventory.setItem(24, named(Material.WRITABLE_BOOK, ChatColor.GOLD + "输出问题清单", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "把异常物品发到聊天栏",
                ChatColor.GRAY + "只输出信息，不移动物品。"
        )));
        inventory.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "← 返回主菜单", List.of(ChatColor.GRAY + "返回 /bind 主菜单。")));
        player.openInventory(inventory);
        service.playSound(player, "gui.open");
    }

    public void openList(Player player) {
        openList(player, 0);
    }

    public void openList(Player player, int page) {
        openList(player, page, false);
    }

    private void openList(Player player, int page, boolean refreshLocations) {
        List<BindingRecord> records = store.byOwner(player.getUniqueId());
        int totalPages = Math.max(1, (records.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        ListHolder holder = new ListHolder(player.getUniqueId(), player.getName(), safePage, totalPages, false);
        Inventory inventory = Bukkit.createInventory(holder, 54, service.message("gui.list-title", "&5我的灵魂绑定"));
        holder.setInventory(inventory);
        fillRecordList(inventory, holder, records, safePage, false, refreshLocations);
        fillFooter(inventory, Material.PURPLE_STAINED_GLASS_PANE);
        inventory.setItem(45, named(safePage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "上一页", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        boolean hasUnlocked = hasUnlockedBinding(player.getUniqueId());
        inventory.setItem(47, named(Material.COMPASS, ChatColor.AQUA + "刷新本页", List.of(
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "重新扫描本页位置",
                ChatColor.GRAY + "找不到物品时再使用。"
        )));
        inventory.setItem(49, named(Material.BARRIER, ChatColor.YELLOW + "返回主菜单", List.of(ChatColor.GRAY + "返回 /bind 主菜单。")));
        inventory.setItem(51, named(hasUnlocked ? Material.REDSTONE_TORCH : Material.SHIELD,
                hasUnlocked ? ChatColor.YELLOW + "一键开启不可离包" : ChatColor.GREEN + "全部已开启不可离包",
                hasUnlocked ? List.of(
                        ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "保护所有未锁定绑定物",
                        ChatColor.GRAY + "不会移动或召回物品。"
                ) : List.of(ChatColor.GRAY + "当前没有需要批量保护的绑定物。")));
        inventory.setItem(53, named(safePage + 1 < totalPages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "下一页", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        player.openInventory(inventory);
        service.playSound(player, "gui.open");
    }

    public void openConfirm(Player player, BindingRecord record, BindingService.ConfirmType type) {
        ConfirmHolder holder = new ConfirmHolder(record.getId(), type);
        Inventory inventory = Bukkit.createInventory(holder, 27, service.message("gui.confirm-title", "&4确认安全召回"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.RED_STAINED_GLASS_PANE);
        List<String> info = new ArrayList<>();
        info.add(ChatColor.GRAY + "绑定者：" + ChatColor.WHITE + record.getOwnerName());
        info.add(ChatColor.GRAY + "当前位置：" + ChatColor.WHITE + record.getLocation().describe());
        info.add(ChatColor.GRAY + "接收要求：" + ChatColor.WHITE + "背包至少预留 1 个空位");
        if (type == BindingService.ConfirmType.LOST) {
            info.add(ChatColor.YELLOW + "丢失召回费用：" + service.formatMoney(service.lostRecallCost()));
            info.add(ChatColor.RED + "确认前会重新扫描位置；若找到原物品，将改走安全召回。");
        } else {
            info.add(ChatColor.YELLOW + "该物品当前由其他玩家持有。");
            if (service.normalRecallCost() > 0.0D) {
                info.add(ChatColor.YELLOW + "普通召回费用：" + service.formatMoney(service.normalRecallCost()));
            }
            info.add(ChatColor.RED + "确认后会先移除原位置物品，再发还给你。");
        }
        info.add(ChatColor.DARK_GRAY + "连续点击会被安全防抖拦截。");
        inventory.setItem(13, displayItem(record, store.ownerIndex(record), true));
        inventory.setItem(11, named(Material.LIME_WOOL, ChatColor.GREEN + "✔ " + ChatColor.stripColor(service.message("gui.confirm-name", "&a确认召回")), info));
        inventory.setItem(15, named(Material.RED_WOOL, ChatColor.RED + "✘ " + ChatColor.stripColor(service.message("gui.cancel-name", "&c取消")), List.of(ChatColor.GRAY + "关闭此确认页面。")));
        player.openInventory(inventory);
        service.playSound(player, "gui.confirm-open");
    }

    private void openPlayerDetail(Player player, UUID recordId) {
        store.find(recordId).ifPresentOrElse(record -> {
            if (!record.getOwnerUuid().equals(player.getUniqueId())) {
                player.sendMessage(service.prefix() + ChatColor.RED + "你只能管理自己的绑定物。");
                service.playSound(player, "error");
                openList(player);
                return;
            }
            DetailHolder holder = new DetailHolder(recordId, false, player.getUniqueId(), player.getName());
            Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.detail-title", "&5绑定物详情"));
            holder.setInventory(inventory);
            fillBorder(inventory, Material.PURPLE_STAINED_GLASS_PANE);
            inventory.setItem(4, displayItem(record, store.ownerIndex(record), true));
            inventory.setItem(11, named(Material.ENDER_PEARL, ChatColor.GREEN + "安全召回", List.of(
                    ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "把物品召回背包",
                    ChatColor.GRAY + "会先移除原物品，防止复制。",
                    ChatColor.GRAY + "普通召回费用：" + ChatColor.WHITE + service.formatMoney(service.normalRecallCost()),
                    ChatColor.GRAY + "原物品移动时会自动失败。"
            )));
            inventory.setItem(13, named(record.isLocked() ? Material.REDSTONE_TORCH : Material.LEVER,
                    ChatColor.YELLOW + "不可离包：" + (record.isLocked() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"), List.of(
                            ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "切换保护状态",
                            ChatColor.GRAY + "当前状态：" + (record.isLocked() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"),
                            ChatColor.GRAY + "开启后阻止丢弃和危险离包。"
                    )));
            inventory.setItem(15, named(Material.COMPASS, ChatColor.AQUA + "刷新位置", List.of(
                    ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "重新扫描真实位置",
                    ChatColor.GRAY + "只刷新记录，不移动物品。"
            )));
            inventory.setItem(29, named(Material.ANVIL, ChatColor.RED + "解除绑定", List.of(
                    ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "进入二次确认",
                    ChatColor.GRAY + "解除费用：" + ChatColor.WHITE + service.formatMoney(service.unbindCost()),
                    ChatColor.GRAY + "解除后将不再受召回与追踪保护。"
            )));
            inventory.setItem(31, named(Material.WRITABLE_BOOK, ChatColor.GOLD + "输出详情", List.of(
                    ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "把完整信息发到聊天栏"
            )));
            inventory.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "← 返回列表", List.of(ChatColor.GRAY + "返回我的绑定物列表。")));
            player.openInventory(inventory);
            service.playSound(player, "gui.open");
        }, () -> {
            player.sendMessage(service.prefix() + ChatColor.RED + "该绑定记录已不存在。");
            service.playSound(player, "error");
            openList(player);
        });
    }

    private void openAdminMenu(Player player) {
        AdminMenuHolder holder = new AdminMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.admin-title", "&4灵魂绑定 · 管理"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(4, named(Material.COMMAND_BLOCK, ChatColor.RED + "⚙ 管理员控制台", List.of(
                ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━",
                ChatColor.GRAY + "高风险功能已按区域排列。",
                ChatColor.GRAY + "扫描和解除操作仍会遵守防复制逻辑。",
                ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━"
        )));
        inventory.setItem(10, named(Material.PLAYER_HEAD, ChatColor.AQUA + "◇ 玩家绑定管理", List.of(ChatColor.GRAY + "选择玩家并查看其绑定物。")));
        inventory.setItem(12, named(Material.HOPPER, ChatColor.GOLD + "▣ 重复物隔离仓库", List.of(ChatColor.GRAY + "查看因重复 UUID 被隔离的物品记录。")));
        inventory.setItem(14, named(Material.FILLED_MAP, ChatColor.LIGHT_PURPLE + "✦ 插件统计面板", List.of(ChatColor.GRAY + "查看绑定数量、状态分布和当前配置。")));
        inventory.setItem(16, named(Material.WRITABLE_BOOK, ChatColor.GREEN + "▣ 一键维护与备份", List.of(
                ChatColor.GRAY + "合并执行：全服安全扫描、数据库安全检查点、手动备份。",
                ChatColor.GRAY + "备份 SQLite 数据库、隔离记录、配置、消息和提醒偏好。",
                ChatColor.RED + "发现重复绑定 UUID 时会隔离多余实例。"
        )));
        inventory.setItem(20, named(Material.RECOVERY_COMPASS, ChatColor.LIGHT_PURPLE + "◇ 数据库备份还原", List.of(
                ChatColor.GRAY + "查看当前数据库备份列表并选择还原。",
                ChatColor.GRAY + "还原前会自动再创建一次完整备份。",
                ChatColor.RED + "高风险操作：还原后会重新加载绑定记录。"
        )));
        inventory.setItem(22, named(Material.REPEATER, ChatColor.GREEN + "↻ 重载配置", List.of(ChatColor.GRAY + "重新加载配置、消息与插件集成。")));
        inventory.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "← 返回主菜单", List.of(ChatColor.GRAY + "返回 /bind 主菜单。")));
        player.openInventory(inventory);
        service.playSound(player, "gui.open");
    }

    private void openAdminStats(Player admin) {
        AdminStatsHolder holder = new AdminStatsHolder();
        Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.admin-stats-title", "&4灵魂绑定 · 插件统计"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.RED_STAINED_GLASS_PANE);
        StatisticSnapshot stats = statistics();
        inventory.setItem(10, named(Material.BOOK, ChatColor.GOLD + "▣ 全局绑定统计", List.of(
                ChatColor.GRAY + "总绑定数量：" + ChatColor.WHITE + stats.total(),
                ChatColor.GRAY + "有绑定记录的玩家：" + ChatColor.WHITE + stats.owners(),
                ChatColor.GRAY + "当前在线玩家：" + ChatColor.WHITE + Bukkit.getOnlinePlayers().size(),
                ChatColor.GRAY + "隔离仓库记录：" + ChatColor.WHITE + stats.quarantine()
        )));
        inventory.setItem(12, named(Material.REDSTONE_TORCH, ChatColor.YELLOW + "◆ 状态分布", List.of(
                ChatColor.GRAY + "不可离包：" + ChatColor.WHITE + stats.locked(),
                ChatColor.GRAY + "玩家背包：" + ChatColor.WHITE + stats.player(),
                ChatColor.GRAY + "临时界面：" + ChatColor.WHITE + stats.temporary(),
                ChatColor.GRAY + "容器或实体容器：" + ChatColor.WHITE + stats.container(),
                ChatColor.GRAY + "掉落物：" + ChatColor.WHITE + stats.dropped(),
                ChatColor.GRAY + "已丢失：" + ChatColor.WHITE + stats.lost()
        )));
        inventory.setItem(14, named(Material.EMERALD, ChatColor.GREEN + "✦ 费用配置", List.of(
                ChatColor.GRAY + "绑定费用：" + ChatColor.WHITE + service.formatMoney(service.bindCost()),
                ChatColor.GRAY + "普通召回费用：" + ChatColor.WHITE + service.formatMoney(service.normalRecallCost()),
                ChatColor.GRAY + "丢失召回费用：" + ChatColor.WHITE + service.formatMoney(service.lostRecallCost()),
                ChatColor.GRAY + "解除绑定费用：" + ChatColor.WHITE + service.formatMoney(service.unbindCost()),
                ChatColor.GRAY + "召回冷却：" + ChatColor.WHITE + service.recallCooldownSeconds() + " 秒"
        )));
        inventory.setItem(16, named(Material.COMPARATOR, ChatColor.AQUA + "⛓ 绑定规则", List.of(
                ChatColor.GRAY + "每名玩家上限：" + ChatColor.WHITE + service.maxPerPlayer(),
                ChatColor.GRAY + "默认不可离包：" + ChatColor.WHITE + (plugin.getConfig().getBoolean("binding.default-locked", false) ? "开启" : "关闭"),
                ChatColor.GRAY + "材料白名单：" + ChatColor.WHITE + (service.bindingWhitelistEnabled() ? "开启" : "关闭"),
                ChatColor.GRAY + "白名单材料数：" + ChatColor.WHITE + service.bindingWhitelistSize(),
                ChatColor.GRAY + "黑名单材料数：" + ChatColor.WHITE + service.bindingBlacklistSize()
        )));
        inventory.setItem(22, named(Material.CLOCK, ChatColor.LIGHT_PURPLE + "◇ 追踪配置", List.of(
                ChatColor.GRAY + "自动轻量扫描：" + ChatColor.WHITE + plugin.getConfig().getLong("binding.scan-interval-ticks", 1200L) + " tick",
                ChatColor.GRAY + "每轮预算：" + ChatColor.WHITE + plugin.getConfig().getInt("binding.scan-records-per-run", 64) + " 条 / " + plugin.getConfig().getLong("binding.scan-time-budget-ms", 10L) + " 毫秒",
                ChatColor.GRAY + "绑定物提醒：" + ChatColor.WHITE + (plugin.getConfig().getBoolean("alerts.enabled", true) ? "开启" : "关闭"),
                ChatColor.GRAY + "提醒冷却：" + ChatColor.WHITE + plugin.getConfig().getLong("alerts.cooldown-seconds", 5L) + " 秒",
                ChatColor.GRAY + "深度扫描 CoreProtect：" + ChatColor.WHITE + (plugin.getConfig().getBoolean("coreprotect.enabled", true) && plugin.getConfig().getBoolean("coreprotect.use-in-auto-scan", true) ? "开启" : "关闭"),
                ChatColor.GRAY + "CoreProtect 查询范围：" + ChatColor.WHITE + plugin.getConfig().getLong("coreprotect.lookup-time-seconds", 604800L) + " 秒",
                ChatColor.GRAY + "CoreProtect 查询半径：" + ChatColor.WHITE + plugin.getConfig().getInt("coreprotect.lookup-radius", 128)
        )));
        inventory.setItem(24, named(Material.LECTERN, ChatColor.AQUA + "▣ 数据库状态与维护", databaseLore()));
        inventory.setItem(26, named(Material.AMETHYST_SHARD, ChatColor.LIGHT_PURPLE + "✦ 优化数据库", List.of(
                ChatColor.GRAY + "执行增量保存、SQLite optimize 与 WAL 检查点。",
                ChatColor.GRAY + "适合更新插件后或长时间运行后手动执行。",
                ChatColor.DARK_GRAY + "不会改变任何绑定记录内容。"
        )));
        inventory.setItem(38, named(Material.COMPASS, ChatColor.AQUA + "↻ 刷新统计", List.of(ChatColor.GRAY + "重新计算当前统计面板。")));
        inventory.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "← 返回管理员控制台", List.of(ChatColor.GRAY + "返回上一页。")));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.open");
    }

    private void openAdminPlayers(Player admin, int page) {
        List<PlayerEntry> players = knownPlayers();
        int totalPages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        AdminPlayersHolder holder = new AdminPlayersHolder(safePage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, 54, service.message("gui.admin-players-title", "&4选择玩家"));
        holder.setInventory(inventory);
        if (players.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, ChatColor.YELLOW + "暂无玩家记录", List.of(ChatColor.GRAY + "当前没有在线玩家，也没有绑定数据。")));
        } else {
            int start = safePage * PAGE_SIZE;
            int end = Math.min(players.size(), start + PAGE_SIZE);
            for (int i = start; i < end; i++) {
                PlayerEntry entry = players.get(i);
                int slot = i - start;
                holder.setPlayer(slot, entry);
                inventory.setItem(slot, named(Material.PLAYER_HEAD, ChatColor.AQUA + entry.name(), List.of(
                        ChatColor.GRAY + "绑定数量：" + store.byOwner(entry.uuid()).size(),
                        ChatColor.DARK_GRAY + "点击管理该玩家的绑定物。"
                )));
            }
        }
        fillFooter(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(45, named(safePage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "← " + ChatColor.stripColor(service.message("gui.previous-page", "&e上一页")), List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        inventory.setItem(49, named(Material.ARROW, ChatColor.YELLOW + "← 返回管理员控制台", List.of(ChatColor.GRAY + "返回上一页。")));
        inventory.setItem(51, named(Material.COMPASS, ChatColor.AQUA + "↻ 刷新玩家列表", List.of(ChatColor.GRAY + "重新读取在线玩家与已有绑定数据。")));
        inventory.setItem(53, named(safePage + 1 < totalPages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + ChatColor.stripColor(service.message("gui.next-page", "&e下一页")) + " →", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.open");
    }

    private void openAdminRecordList(Player admin, UUID targetUuid, String targetName, int page) {
        openAdminRecordList(admin, targetUuid, targetName, page, false);
    }

    private void openAdminRecordList(Player admin, UUID targetUuid, String targetName, int page, boolean refreshLocations) {
        List<BindingRecord> records = store.byOwner(targetUuid);
        int totalPages = Math.max(1, (records.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        ListHolder holder = new ListHolder(targetUuid, targetName, safePage, totalPages, true);
        Inventory inventory = Bukkit.createInventory(holder, 54, service.message("gui.admin-records-title", "&4玩家绑定物"));
        holder.setInventory(inventory);
        fillRecordList(inventory, holder, records, safePage, true, refreshLocations);
        fillFooter(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(45, named(safePage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "← " + ChatColor.stripColor(service.message("gui.previous-page", "&e上一页")), List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        boolean hasUnlocked = hasUnlockedBinding(targetUuid);
        inventory.setItem(47, named(hasUnlocked ? Material.REDSTONE_TORCH : Material.SHIELD,
                hasUnlocked ? ChatColor.YELLOW + "⛓ 一键开启该玩家全部不可离包" : ChatColor.GREEN + "✔ 该玩家全部已开启不可离包",
                hasUnlocked ? List.of(
                        ChatColor.GRAY + "目标玩家：" + ChatColor.WHITE + targetName,
                        ChatColor.GRAY + "为该玩家所有未锁定绑定物开启不可离包。",
                        ChatColor.RED + "管理员批量操作会进入二次确认页。"
                ) : List.of(ChatColor.GRAY + "该玩家当前没有需要批量保护的绑定物。")));
        inventory.setItem(49, named(Material.ARROW, ChatColor.YELLOW + "← 返回玩家列表", List.of(ChatColor.GRAY + "当前玩家：" + targetName)));
        inventory.setItem(51, named(Material.COMPASS, ChatColor.AQUA + "↻ 刷新当前页", List.of(
                ChatColor.GRAY + "执行一次深度扫描并刷新本页绑定物位置。",
                ChatColor.YELLOW + "普通翻页只读取记录位置，点击这里才会深度扫描。",
                ChatColor.DARK_GRAY + "不会生成或移动任何物品。"
        )));
        inventory.setItem(53, named(safePage + 1 < totalPages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + ChatColor.stripColor(service.message("gui.next-page", "&e下一页")) + " →", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.open");
    }

    private void openAdminDetail(Player admin, UUID recordId, UUID targetUuid, String targetName) {
        store.find(recordId).ifPresentOrElse(record -> {
            DetailHolder holder = new DetailHolder(recordId, true, targetUuid, targetName);
            Inventory inventory = Bukkit.createInventory(holder, 45, service.message("gui.detail-title", "&5绑定物详情"));
            holder.setInventory(inventory);
            fillBorder(inventory, Material.RED_STAINED_GLASS_PANE);
            inventory.setItem(4, displayItem(record, store.ownerIndex(record), true));
            inventory.setItem(19, named(Material.ENDER_PEARL, ChatColor.GREEN + "➤ 管理员安全召回", List.of(ChatColor.GRAY + "免费将物品发还给当前绑定者。")));
            inventory.setItem(21, named(Material.COMPASS, ChatColor.AQUA + "◎ 重新扫描位置", List.of(ChatColor.GRAY + "重新扫描真实物品并更新记录位置。")));
            inventory.setItem(23, named(record.isLocked() ? Material.REDSTONE_TORCH : Material.LEVER,
                    ChatColor.YELLOW + "⛓ 切换不可离包", List.of(ChatColor.GRAY + "当前状态：" + (record.isLocked() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"))));
            inventory.setItem(25, named(Material.NAME_TAG, ChatColor.LIGHT_PURPLE + "◇ 修改绑定者", List.of(ChatColor.GRAY + "从玩家列表中选择新的绑定者。")));
            inventory.setItem(31, named(Material.ANVIL, ChatColor.RED + "⚠ 管理员解除绑定", List.of(ChatColor.RED + "此操作需要二次确认。")));
            inventory.setItem(33, named(Material.WRITABLE_BOOK, ChatColor.GOLD + "▣ 聊天输出详情", List.of(
                    ChatColor.GRAY + "将完整绑定信息输出到聊天栏。",
                    ChatColor.DARK_GRAY + "适合人工审计和故障排查。"
            )));
            inventory.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "← 返回绑定列表", List.of(ChatColor.GRAY + "返回 " + targetName + " 的绑定物列表。")));
            admin.openInventory(inventory);
            service.playSound(admin, "gui.open");
        }, () -> {
            admin.sendMessage(service.prefix() + ChatColor.RED + "该绑定记录已不存在。");
            service.playSound(admin, "error");
            openAdminRecordList(admin, targetUuid, targetName, 0);
        });
    }

    private void openOwnerSelect(Player admin, UUID recordId, int page) {
        List<PlayerEntry> players = knownPlayers();
        int totalPages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        OwnerSelectHolder holder = new OwnerSelectHolder(recordId, safePage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, 54, service.message("gui.owner-select-title", "&4选择新绑定者"));
        holder.setInventory(inventory);
        if (players.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, ChatColor.YELLOW + "暂无可选玩家", List.of(ChatColor.GRAY + "至少需要一名在线玩家或已有绑定记录的玩家。")));
        } else {
            int start = safePage * PAGE_SIZE;
            int end = Math.min(players.size(), start + PAGE_SIZE);
            for (int i = start; i < end; i++) {
                PlayerEntry entry = players.get(i);
                int slot = i - start;
                holder.setPlayer(slot, entry);
                inventory.setItem(slot, named(Material.PLAYER_HEAD, ChatColor.AQUA + entry.name(), List.of(ChatColor.GRAY + "点击设为新的绑定者。")));
            }
        }
        fillFooter(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(45, named(safePage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "← " + ChatColor.stripColor(service.message("gui.previous-page", "&e上一页")), List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        inventory.setItem(49, named(Material.ARROW, ChatColor.YELLOW + "← 返回详情", List.of(ChatColor.GRAY + "取消修改并返回详情。")));
        inventory.setItem(53, named(safePage + 1 < totalPages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + ChatColor.stripColor(service.message("gui.next-page", "&e下一页")) + " →", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.open");
    }

    private void openActionConfirm(Player player, UUID recordId, GuiAction action, boolean admin, UUID targetUuid, String targetName) {
        ActionConfirmHolder holder = new ActionConfirmHolder(recordId, action, admin, targetUuid, targetName);
        Inventory inventory = Bukkit.createInventory(holder, 27, service.message("gui.confirm-title", "&4确认敏感操作"));
        holder.setInventory(inventory);
        fillBorder(inventory, admin ? Material.RED_STAINED_GLASS_PANE : Material.PURPLE_STAINED_GLASS_PANE);
        if (action == GuiAction.LOCK_ALL) {
            inventory.setItem(13, named(Material.REDSTONE_TORCH, ChatColor.YELLOW + "⛓ 批量开启不可离包", List.of(
                    ChatColor.GRAY + "目标玩家：" + ChatColor.WHITE + (targetName == null ? "未知玩家" : targetName),
                    ChatColor.GRAY + "只会为未锁定的绑定物开启保护。",
                    ChatColor.GRAY + "可定位的真实物品会同步刷新 Lore 与保护标记。",
                    ChatColor.RED + "不会召回、生成、解除绑定或清空任何物品。"
            )));
        } else {
            store.find(recordId).ifPresent(record -> inventory.setItem(13, displayItem(record, store.ownerIndex(record), true)));
        }
        String name = switch (action) {
            case UNBIND -> "确认解除绑定";
            case LOCK_ALL -> "确认批量开启不可离包";
        };
        List<String> confirmLore = action == GuiAction.LOCK_ALL
                ? List.of(
                ChatColor.RED + "请确认你要批量开启保护。",
                ChatColor.GRAY + "此操作不会降低防复制安全等级。"
        )
                : List.of(ChatColor.RED + "请再次确认，执行后将立即生效。");
        inventory.setItem(11, named(Material.LIME_WOOL, ChatColor.GREEN + "✔ " + name, confirmLore));
        inventory.setItem(15, named(Material.RED_WOOL, ChatColor.RED + "✘ " + ChatColor.stripColor(service.message("gui.cancel-name", "&c取消")), List.of(ChatColor.GRAY + "返回上一页。")));
        player.openInventory(inventory);
        service.playSound(player, "gui.confirm-open");
    }

    private void openQuarantine(Player admin, int page) {
        List<QuarantineEntry> entries = quarantineEntries();
        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        QuarantineHolder holder = new QuarantineHolder(safePage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, 54, service.message("gui.quarantine-title", "&4重复物隔离仓库"));
        holder.setInventory(inventory);
        if (entries.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, ChatColor.YELLOW + "暂无隔离记录", List.of(ChatColor.GRAY + "当前没有重复绑定物隔离记录。")));
        } else {
            int start = safePage * PAGE_SIZE;
            int end = Math.min(entries.size(), start + PAGE_SIZE);
            for (int i = start; i < end; i++) {
                QuarantineEntry entry = entries.get(i);
                ItemStack display = entry.item() == null ? new ItemStack(Material.BARRIER) : entry.item().clone();
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("");
                    lore.add(ChatColor.GRAY + "绑定编号：" + entry.bindingId());
                    lore.add(ChatColor.GRAY + "发现位置：" + entry.location());
                    lore.add(ChatColor.RED + "隔离物仅作审计保存，不会自动发放。");
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
                inventory.setItem(i - start, display);
            }
        }
        fillFooter(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(45, named(safePage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "← " + ChatColor.stripColor(service.message("gui.previous-page", "&e上一页")), List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        inventory.setItem(49, named(Material.ARROW, ChatColor.YELLOW + "← 返回管理员控制台", List.of(ChatColor.GRAY + "返回上一页。")));
        inventory.setItem(51, named(Material.COMPASS, ChatColor.AQUA + "↻ 刷新隔离列表", List.of(ChatColor.GRAY + "重新读取当前隔离仓库记录。")));
        inventory.setItem(53, named(safePage + 1 < totalPages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + ChatColor.stripColor(service.message("gui.next-page", "&e下一页")) + " →", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.open");
    }

    private void openBackupList(Player admin, int page) {
        List<BackupEntry> backups = backupEntries();
        int totalPages = Math.max(1, (backups.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        BackupListHolder holder = new BackupListHolder(safePage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, 54, service.message("gui.backups-title", "&4数据库备份还原"));
        holder.setInventory(inventory);
        if (backups.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, ChatColor.YELLOW + "暂无数据库备份", List.of(
                    ChatColor.GRAY + "还没有找到可还原的数据库备份。",
                    ChatColor.GRAY + "新备份位于 Backup 目录，每次备份一个独立文件夹。",
                    ChatColor.GRAY + "可先返回管理员菜单执行“一键维护与备份”。"
            )));
        } else {
            int start = safePage * PAGE_SIZE;
            int end = Math.min(backups.size(), start + PAGE_SIZE);
            for (int i = start; i < end; i++) {
                BackupEntry entry = backups.get(i);
                int slot = i - start;
                holder.setBackup(slot, entry.file());
                inventory.setItem(slot, backupDisplay(entry, i + 1));
            }
        }
        fillFooter(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(45, named(safePage > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + "← " + ChatColor.stripColor(service.message("gui.previous-page", "&e上一页")), List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        inventory.setItem(49, named(Material.ARROW, ChatColor.YELLOW + "← 返回管理员控制台", List.of(ChatColor.GRAY + "返回上一页。")));
        inventory.setItem(51, named(Material.COMPASS, ChatColor.AQUA + "↻ 刷新备份列表", List.of(ChatColor.GRAY + "重新读取 Backup 目录中的数据库备份。")));
        inventory.setItem(53, named(safePage + 1 < totalPages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.YELLOW + ChatColor.stripColor(service.message("gui.next-page", "&e下一页")) + " →", List.of(ChatColor.GRAY + "页码：" + (safePage + 1) + "/" + totalPages)));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.open");
    }

    private void openRestoreConfirm(Player admin, File backupFile) {
        BackupEntry entry = backupEntry(backupFile);
        RestoreConfirmHolder holder = new RestoreConfirmHolder(backupFile);
        Inventory inventory = Bukkit.createInventory(holder, 27, service.message("gui.restore-confirm-title", "&4确认还原数据库"));
        holder.setInventory(inventory);
        fillBorder(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(13, backupDisplay(entry, 1));
        inventory.setItem(11, named(Material.LIME_WOOL, ChatColor.GREEN + "✔ 确认还原此备份", List.of(
                ChatColor.RED + "还原前会自动再创建一次完整备份。",
                ChatColor.RED + "还原会覆盖当前 SQLite 数据库并重新加载内存记录。",
                ChatColor.GRAY + "备份文件夹：" + ChatColor.WHITE + entry.folderName(),
                ChatColor.GRAY + "数据库文件：" + ChatColor.WHITE + entry.file().getName()
        )));
        inventory.setItem(15, named(Material.RED_WOOL, ChatColor.RED + "✘ 取消还原", List.of(ChatColor.GRAY + "返回备份列表，不修改数据库。")));
        admin.openInventory(inventory);
        service.playSound(admin, "gui.confirm-open");
    }

    public boolean handleClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof MainHolder) {
            return handleMain(event);
        }
        if (holder instanceof PlayerStatsHolder) {
            return handlePlayerStats(event);
        }
        if (holder instanceof HelpHolder) {
            return handleHelp(event);
        }
        if (holder instanceof ListHolder listHolder) {
            return handleList(event, listHolder);
        }
        if (holder instanceof DetailHolder detailHolder) {
            return handleDetail(event, detailHolder);
        }
        if (holder instanceof ConfirmHolder confirmHolder) {
            return handleRecallConfirm(event, confirmHolder);
        }
        if (holder instanceof ActionConfirmHolder confirmHolder) {
            return handleActionConfirm(event, confirmHolder);
        }
        if (holder instanceof AdminMenuHolder) {
            return handleAdminMenu(event);
        }
        if (holder instanceof AdminStatsHolder) {
            return handleAdminStats(event);
        }
        if (holder instanceof AdminPlayersHolder playersHolder) {
            return handleAdminPlayers(event, playersHolder);
        }
        if (holder instanceof OwnerSelectHolder ownerSelectHolder) {
            return handleOwnerSelect(event, ownerSelectHolder);
        }
        if (holder instanceof QuarantineHolder quarantineHolder) {
            return handleQuarantine(event, quarantineHolder);
        }
        if (holder instanceof BackupListHolder backupListHolder) {
            return handleBackupList(event, backupListHolder);
        }
        if (holder instanceof RestoreConfirmHolder restoreConfirmHolder) {
            return handleRestoreConfirm(event, restoreConfirmHolder);
        }
        return false;
    }

    private boolean handleMain(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        switch (event.getRawSlot()) {
            case 11 -> {
                service.playSound(player, "gui.bind");
                player.closeInventory();
                if (service.bindMainHand(player)) {
                    Bukkit.getScheduler().runTask(plugin, () -> openList(player, 0));
                }
            }
            case 13 -> {
                service.playSound(player, "gui.detail");
                openList(player, 0);
            }
            case 15 -> {
                service.togglePlayerAlerts(player);
                openMain(player);
            }
            case 33 -> {
                service.playSound(player, "gui.detail");
                openHelp(player);
            }
            case 29 -> {
                service.playSound(player, "gui.stats");
                openPlayerStats(player);
            }
            case 40 -> {
                if (player.hasPermission("bind.admin")) {
                    service.playSound(player, "gui.admin");
                    openAdminMenu(player);
                } else {
                    service.playSound(player, "gui.disabled");
                }
            }
            default -> {
                service.playSound(player, "gui.click");
            }
        }
        return true;
    }

    private boolean handleHelp(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (event.getRawSlot() == 40) {
            service.playSound(player, "gui.back");
            openMain(player);
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handlePlayerStats(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        switch (event.getRawSlot()) {
            case 15 -> {
                service.playSound(player, "gui.refresh");
                List<BindingRecord> records = store.byOwner(player.getUniqueId());
                service.refreshLocationsForDisplay(records);
                sendPlayerSummary(player, records);
                openPlayerStats(player);
            }
            case 11 -> {
                service.playSound(player, "gui.detail");
                openList(player, 0);
            }
            case 31 -> {
                service.togglePlayerAlerts(player);
                openPlayerStats(player);
            }
            case 24 -> {
                service.playSound(player, "gui.detail");
                List<BindingRecord> records = store.byOwner(player.getUniqueId());
                sendProblemRecords(player, records);
                openPlayerStats(player);
            }
            case 20 -> {
                List<BindingRecord> records = store.byOwner(player.getUniqueId());
                BindingRecord urgent = firstAttentionRecord(records);
                if (urgent == null) {
                    service.playSound(player, "gui.disabled");
                    player.sendMessage(service.prefix() + ChatColor.GREEN + "当前没有需要立即处理的绑定物。 ");
                    openPlayerStats(player);
                } else {
                    service.playSound(player, "gui.detail");
                    openPlayerDetail(player, urgent.getId());
                }
            }
            case 22 -> {
                if (hasUnlockedBinding(player.getUniqueId())) {
                    service.playSound(player, "gui.confirm-open");
                    openActionConfirm(player, null, GuiAction.LOCK_ALL, false, player.getUniqueId(), player.getName());
                } else {
                    service.playSound(player, "gui.disabled");
                    player.sendMessage(service.prefix() + ChatColor.GREEN + "你的绑定物都已开启不可离包，无需批量处理。 ");
                    openPlayerStats(player);
                }
            }
            case 40 -> {
                service.playSound(player, "gui.back");
                openMain(player);
            }
            default -> service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleList(InventoryClickEvent event, ListHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (holder.admin && !ensureAdmin(player)) {
            return true;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) {
            service.playSound(player, "gui.page");
            if (holder.admin) {
                openAdminRecordList(player, holder.owner, holder.ownerName, holder.page - 1);
            } else {
                openList(player, holder.page - 1);
            }
            return true;
        }
        if (slot == 53 && holder.page + 1 < holder.totalPages) {
            service.playSound(player, "gui.page");
            if (holder.admin) {
                openAdminRecordList(player, holder.owner, holder.ownerName, holder.page + 1);
            } else {
                openList(player, holder.page + 1);
            }
            return true;
        }
        if (slot == 49) {
            service.playSound(player, "gui.back");
            if (holder.admin) {
                openAdminPlayers(player, 0);
            } else {
                openMain(player);
            }
            return true;
        }
        if (slot == 51) {
            if (hasUnlockedBinding(holder.owner)) {
                service.playSound(player, "gui.confirm-open");
                openActionConfirm(player, null, GuiAction.LOCK_ALL, holder.admin, holder.owner, holder.ownerName);
            } else {
                service.playSound(player, "gui.disabled");
                player.sendMessage(service.prefix() + ChatColor.GREEN + "当前没有需要批量开启不可离包的绑定物。 ");
            }
            return true;
        }
        if (slot == 47) {
            service.playSound(player, "gui.refresh");
            if (holder.admin) {
                openAdminRecordList(player, holder.owner, holder.ownerName, holder.page, true);
            } else {
                openList(player, holder.page, true);
            }
            return true;
        }
        if (!holder.admin && store.byOwner(player.getUniqueId()).isEmpty()) {
            if (slot == 20) {
                service.playSound(player, "gui.bind");
                player.closeInventory();
                if (service.bindMainHand(player)) {
                    Bukkit.getScheduler().runTask(plugin, () -> openList(player, 0));
                }
                return true;
            }
            if (slot == 24) {
                service.playSound(player, "gui.detail");
                openHelp(player);
                return true;
            }
        }
        UUID id = holder.getRecord(slot);
        if (id != null) {
            if (holder.admin) {
                service.playSound(player, "gui.detail");
                openAdminDetail(player, id, holder.owner, holder.ownerName);
            } else if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT) {
                service.playSound(player, "gui.recall-select");
                store.find(id).ifPresent(record -> service.requestRecall(player, record, false));
            } else {
                service.playSound(player, "gui.detail");
                openPlayerDetail(player, id);
            }
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleDetail(InventoryClickEvent event, DetailHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        int slot = event.getRawSlot();
        if (!holder.admin) {
            if (slot == 11) {
                service.playSound(player, "gui.recall-select");
                store.find(holder.recordId).ifPresent(record -> service.requestRecall(player, record, false));
            } else if (slot == 13) {
                service.playSound(player, "gui.toggle");
                store.find(holder.recordId).ifPresent(record -> {
                    service.setLocked(player, record, !record.isLocked());
                    openPlayerDetail(player, record.getId());
                });
            } else if (slot == 15) {
                store.find(holder.recordId).ifPresent(record -> {
                    service.playSound(player, "gui.refresh");
                    service.scanForOwnerOrAdmin(player, record);
                    openPlayerDetail(player, record.getId());
                });
            } else if (slot == 29) {
                service.playSound(player, "gui.confirm-open");
                openActionConfirm(player, holder.recordId, GuiAction.UNBIND, false, holder.targetUuid, holder.targetName);
            } else if (slot == 31) {
                store.find(holder.recordId).ifPresent(record -> {
                    service.playSound(player, "gui.detail");
                    service.sendRecordInfo(player, record);
                });
            } else if (slot == 40) {
                service.playSound(player, "gui.back");
                openList(player, 0);
            } else {
                service.playSound(player, "gui.click");
            }
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        switch (slot) {
            case 19 -> {
                service.playSound(player, "gui.recall-select");
                store.find(holder.recordId).ifPresent(record -> service.adminRecall(player, record));
            }
            case 21 -> store.find(holder.recordId).ifPresent(record -> {
                service.playSound(player, "gui.refresh");
                service.scanForAdmin(player, record);
                openAdminDetail(player, record.getId(), holder.targetUuid, holder.targetName);
            });
            case 23 -> store.find(holder.recordId).ifPresent(record -> {
                service.playSound(player, "gui.toggle");
                service.setLocked(player, record, !record.isLocked());
                openAdminDetail(player, record.getId(), holder.targetUuid, holder.targetName);
            });
            case 25 -> {
                service.playSound(player, "gui.owner-select");
                openOwnerSelect(player, holder.recordId, 0);
            }
            case 31 -> {
                service.playSound(player, "gui.confirm-open");
                openActionConfirm(player, holder.recordId, GuiAction.UNBIND, true, holder.targetUuid, holder.targetName);
            }
            case 33 -> store.find(holder.recordId).ifPresent(record -> {
                service.playSound(player, "gui.detail");
                service.sendRecordInfo(player, record);
            });
            case 40 -> {
                service.playSound(player, "gui.back");
                openAdminRecordList(player, holder.targetUuid, holder.targetName, 0);
            }
            default -> {
                service.playSound(player, "gui.click");
            }
        }
        return true;
    }

    private boolean handleRecallConfirm(InventoryClickEvent event, ConfirmHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (event.getRawSlot() == 11) {
            if (isFastConfirmClick(player)) {
                return true;
            }
            service.playSound(player, "gui.confirm");
            player.closeInventory();
            service.confirmRecall(player, holder.recordId, holder.type);
        } else if (event.getRawSlot() == 15) {
            service.playSound(player, "gui.cancel");
            player.closeInventory();
            player.sendMessage(service.prefix() + ChatColor.YELLOW + "已取消本次召回。");
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleActionConfirm(InventoryClickEvent event, ActionConfirmHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (holder.admin && !ensureAdmin(player)) {
            return true;
        }
        if (event.getRawSlot() == 11) {
            if (isFastConfirmClick(player)) {
                return true;
            }
            service.playSound(player, "gui.confirm");
            if (holder.action == GuiAction.UNBIND) {
                var optional = store.find(holder.recordId);
                if (optional.isEmpty()) {
                    player.sendMessage(service.prefix() + ChatColor.RED + "该绑定记录已不存在，本次操作已取消。 ");
                    service.playSound(player, "error");
                } else {
                    service.unbind(player, optional.get(), holder.admin);
                }
                if (holder.admin) {
                    openAdminRecordList(player, holder.targetUuid, holder.targetName, 0);
                } else {
                    openList(player, 0);
                }
            } else if (holder.action == GuiAction.LOCK_ALL) {
                service.setAllLocked(player, holder.targetUuid, holder.targetName, holder.admin);
                if (holder.admin) {
                    openAdminRecordList(player, holder.targetUuid, holder.targetName, 0);
                } else {
                    openList(player, 0);
                }
            }
        } else if (event.getRawSlot() == 15) {
            service.playSound(player, "gui.cancel");
            if (holder.action == GuiAction.LOCK_ALL) {
                if (holder.admin) {
                    openAdminRecordList(player, holder.targetUuid, holder.targetName, 0);
                } else {
                    openList(player, 0);
                }
            } else if (holder.admin) {
                openAdminDetail(player, holder.recordId, holder.targetUuid, holder.targetName);
            } else {
                openPlayerDetail(player, holder.recordId);
            }
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleAdminMenu(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        switch (event.getRawSlot()) {
            case 10 -> {
                service.playSound(player, "gui.admin");
                openAdminPlayers(player, 0);
            }
            case 12 -> {
                service.playSound(player, "gui.admin");
                openQuarantine(player, 0);
            }
            case 14 -> {
                service.playSound(player, "gui.stats");
                openAdminStats(player);
            }
            case 16 -> {
                runAdminMaintenance(player);
            }
            case 20 -> {
                service.playSound(player, "gui.backup");
                openBackupList(player, 0);
            }
            case 22 -> {
                service.playSound(player, "gui.reload");
                plugin.reloadBinder();
                player.sendMessage(service.prefix() + ChatColor.GREEN + "配置、消息与插件集成已重新加载。当前品牌：" + plugin.getMessages().text("release.brand", "Binder") + "。 ");
                openAdminMenu(player);
            }
            case 40 -> {
                service.playSound(player, "gui.back");
                openMain(player);
            }
            default -> {
                service.playSound(player, "gui.click");
            }
        }
        return true;
    }

    private boolean handleBackupList(InventoryClickEvent event, BackupListHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) {
            service.playSound(player, "gui.page");
            openBackupList(player, holder.page - 1);
        } else if (slot == 53 && holder.page + 1 < holder.totalPages) {
            service.playSound(player, "gui.page");
            openBackupList(player, holder.page + 1);
        } else if (slot == 49) {
            service.playSound(player, "gui.back");
            openAdminMenu(player);
        } else if (slot == 51) {
            service.playSound(player, "gui.refresh");
            openBackupList(player, holder.page);
        } else {
            File backup = holder.getBackup(slot);
            if (backup != null) {
                service.playSound(player, "gui.confirm-open");
                openRestoreConfirm(player, backup);
            } else {
                service.playSound(player, "gui.click");
            }
        }
        return true;
    }

    private boolean handleRestoreConfirm(InventoryClickEvent event, RestoreConfirmHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        if (event.getRawSlot() == 11) {
            if (isFastConfirmClick(player)) {
                return true;
            }
            service.playSound(player, "gui.confirm");
            player.closeInventory();
            restoreBackup(player, holder.backupFile);
        } else if (event.getRawSlot() == 15) {
            service.playSound(player, "gui.cancel");
            openBackupList(player, 0);
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleAdminStats(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        if (event.getRawSlot() == 24) {
            runDatabaseCheckpoint(player);
            openAdminStats(player);
        } else if (event.getRawSlot() == 26) {
            runDatabaseOptimize(player);
            openAdminStats(player);
        } else if (event.getRawSlot() == 38) {
            service.playSound(player, "gui.refresh");
            openAdminStats(player);
        } else if (event.getRawSlot() == 40) {
            service.playSound(player, "gui.back");
            openAdminMenu(player);
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleAdminPlayers(InventoryClickEvent event, AdminPlayersHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) {
            service.playSound(player, "gui.page");
            openAdminPlayers(player, holder.page - 1);
        } else if (slot == 53 && holder.page + 1 < holder.totalPages) {
            service.playSound(player, "gui.page");
            openAdminPlayers(player, holder.page + 1);
        } else if (slot == 49) {
            service.playSound(player, "gui.back");
            openAdminMenu(player);
        } else if (slot == 51) {
            service.playSound(player, "gui.refresh");
            openAdminPlayers(player, holder.page);
        } else {
            PlayerEntry entry = holder.getPlayer(slot);
            if (entry != null) {
                service.playSound(player, "gui.detail");
                openAdminRecordList(player, entry.uuid(), entry.name(), 0);
            } else {
                service.playSound(player, "gui.click");
            }
        }
        return true;
    }

    private boolean handleOwnerSelect(InventoryClickEvent event, OwnerSelectHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) {
            service.playSound(player, "gui.page");
            openOwnerSelect(player, holder.recordId, holder.page - 1);
            return true;
        }
        if (slot == 53 && holder.page + 1 < holder.totalPages) {
            service.playSound(player, "gui.page");
            openOwnerSelect(player, holder.recordId, holder.page + 1);
            return true;
        }
        if (slot == 49) {
            service.playSound(player, "gui.back");
            store.find(holder.recordId).ifPresent(record -> openAdminDetail(player, record.getId(), record.getOwnerUuid(), record.getOwnerName()));
            return true;
        }
        PlayerEntry entry = holder.getPlayer(slot);
        if (entry != null) {
            service.playSound(player, "gui.owner-select");
            store.find(holder.recordId).ifPresent(record -> {
                if (record.getOwnerUuid().equals(entry.uuid())) {
                    player.sendMessage(service.prefix() + ChatColor.YELLOW + "该玩家已经是当前绑定者，无需重复修改。 ");
                    service.playSound(player, "gui.disabled");
                    openAdminDetail(player, record.getId(), record.getOwnerUuid(), record.getOwnerName());
                    return;
                }
                service.changeOwner(player, record, entry.uuid(), entry.name());
                openAdminRecordList(player, record.getOwnerUuid(), record.getOwnerName(), 0);
            });
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private boolean handleQuarantine(InventoryClickEvent event, QuarantineHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !isTopClick(event)) {
            return true;
        }
        if (!ensureAdmin(player)) {
            return true;
        }
        if (event.getRawSlot() == 45 && holder.page > 0) {
            service.playSound(player, "gui.page");
            openQuarantine(player, holder.page - 1);
        } else if (event.getRawSlot() == 53 && holder.page + 1 < holder.totalPages) {
            service.playSound(player, "gui.page");
            openQuarantine(player, holder.page + 1);
        } else if (event.getRawSlot() == 49) {
            service.playSound(player, "gui.back");
            openAdminMenu(player);
        } else if (event.getRawSlot() == 51) {
            service.playSound(player, "gui.refresh");
            openQuarantine(player, holder.page);
        } else {
            service.playSound(player, "gui.click");
        }
        return true;
    }

    private void fillRecordList(Inventory inventory, ListHolder holder, List<BindingRecord> records, int page, boolean admin, boolean refreshLocations) {
        if (records.isEmpty()) {
            if (!admin) {
                inventory.setItem(20, named(Material.ENCHANTED_BOOK, ChatColor.GREEN + "立即绑定", List.of(
                        ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "绑定主手物品",
                        ChatColor.GRAY + "要求数量必须为 1。"
                )));
                inventory.setItem(24, named(Material.BOOK, ChatColor.GOLD + "查看帮助", List.of(
                        ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "查看三步说明"
                )));
            }
            inventory.setItem(22, named(Material.BARRIER, ChatColor.RED + "暂无绑定物", List.of(
                    ChatColor.GRAY + "先绑定主手物品，再回到这里管理。"
            )));
            return;
        }
        int start = page * PAGE_SIZE;
        int end = Math.min(records.size(), start + PAGE_SIZE);
        List<BindingRecord> pageRecords = new ArrayList<>(records.subList(start, end));
        if (refreshLocations) {
            service.refreshLocationsForDisplay(pageRecords);
        }
        for (int i = start; i < end; i++) {
            BindingRecord record = records.get(i);
            int slot = i - start;
            inventory.setItem(slot, displayItem(record, i + 1, false, admin));
            holder.setRecord(slot, record.getId());
        }
    }

    private ItemStack displayItem(BindingRecord record, int index, boolean detail) {
        return displayItem(record, index, detail, false);
    }

    private ItemStack displayItem(BindingRecord record, int index, boolean detail, boolean adminList) {
        ItemStack item = service.isEmpty(record.getItem()) ? new ItemStack(Material.BARRIER) : record.getItem().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(statusColor(record) + statusIcon(record) + " #" + index + " " + displayName(record));
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "状态：" + statusColor(record) + statusLabel(record));
            lore.add(service.statusLine(record));
            lore.add(ChatColor.GRAY + "不可离包：" + ChatColor.WHITE + (record.isLocked() ? "开启" : "关闭"));
            if (detail) {
                lore.add(ChatColor.GRAY + "记录更新：" + ChatColor.WHITE + formatAge(record.getUpdatedAt()));
                lore.add(ChatColor.GRAY + "推荐：" + ChatColor.WHITE + recommendedAction(record));
                lore.add(ChatColor.GRAY + "绑定者：" + ChatColor.WHITE + record.getOwnerName());
                lore.add(ChatColor.GRAY + "绑定 UUID：" + ChatColor.WHITE + record.getId());
                lore.add(ChatColor.GRAY + "记录位置：" + ChatColor.WHITE + record.getLocation().describe());
            } else if (adminList) {
                lore.add(ChatColor.GRAY + "绑定者：" + ChatColor.WHITE + record.getOwnerName());
                lore.add(ChatColor.GRAY + "绑定 UUID：" + ChatColor.WHITE + record.getId());
                lore.add(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━");
                lore.add(ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "进入管理员管理详情");
                lore.add(ChatColor.YELLOW + "详情中可执行：" + ChatColor.WHITE + "召回、扫描、改绑定者、解除绑定");
            } else {
                lore.add(ChatColor.YELLOW + "左键：" + ChatColor.WHITE + "安全召回");
                lore.add(ChatColor.YELLOW + "右键：" + ChatColor.WHITE + "查看详情");
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatAge(long timestamp) {
        if (timestamp <= 0L) {
            return "未知";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60L) {
            return "刚刚";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + " 小时前";
        }
        long days = hours / 24L;
        return days + " 天前";
    }

    private String displayName(BindingRecord record) {
        ItemStack item = record.getItem();
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String stripped = ChatColor.stripColor(meta.getDisplayName());
                if (stripped != null && !stripped.isBlank()) {
                    return stripped;
                }
            }
        }
        return "绑定物";
    }

    private ChatColor statusColor(BindingRecord record) {
        return switch (record.getLocation().getType()) {
            case LOST -> ChatColor.RED;
            case DROPPED, TEMPORARY -> ChatColor.YELLOW;
            case CONTAINER -> ChatColor.AQUA;
            case PLAYER -> record.getLocation().getHolderUuid() != null && !record.getLocation().getHolderUuid().equals(record.getOwnerUuid())
                    ? ChatColor.GOLD
                    : ChatColor.GREEN;
        };
    }

    private String statusIcon(BindingRecord record) {
        return switch (record.getLocation().getType()) {
            case LOST -> "✘";
            case DROPPED -> "⌾";
            case TEMPORARY -> "◌";
            case CONTAINER -> "◆";
            case PLAYER -> record.getLocation().getHolderUuid() != null && !record.getLocation().getHolderUuid().equals(record.getOwnerUuid()) ? "⚠" : "✔";
        };
    }

    private String statusLabel(BindingRecord record) {
        return switch (record.getLocation().getType()) {
            case LOST -> "已丢失";
            case DROPPED -> "掉落物";
            case TEMPORARY -> "临时界面";
            case CONTAINER -> "容器或实体容器";
            case PLAYER -> record.getLocation().getHolderUuid() != null && !record.getLocation().getHolderUuid().equals(record.getOwnerUuid()) ? "他人持有" : "自己背包";
        };
    }

    private String recommendedAction(BindingRecord record) {
        return switch (record.getLocation().getType()) {
            case LOST -> "进入确认页执行丢失召回";
            case DROPPED -> "尽快拾取或安全召回";
            case TEMPORARY -> "等待界面关闭后刷新，或确认召回";
            case CONTAINER -> "可直接安全召回，或前往记录位置取回";
            case PLAYER -> record.getLocation().getHolderUuid() != null && !record.getLocation().getHolderUuid().equals(record.getOwnerUuid())
                    ? "由其他玩家持有，召回需二次确认"
                    : "状态正常，可右键查看详情";
        };
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBorder(Inventory inventory, Material material) {
        int rows = inventory.getSize() / 9;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) {
                inventory.setItem(slot, decorative(material));
            }
        }
    }

    private void fillFooter(Inventory inventory, Material material) {
        int start = inventory.getSize() - 9;
        for (int slot = start; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, decorative(material));
        }
    }

    private ItemStack decorative(Material material) {
        return named(material, " ", List.of());
    }

    private List<String> mainInfoLore(Player player) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "你的绑定数量：" + ChatColor.WHITE + store.byOwner(player.getUniqueId()).size() + "/" + service.maxPerPlayer());
        lore.add(ChatColor.GRAY + "绑定费用：" + ChatColor.WHITE + service.formatMoney(service.bindCost()));
        lore.add(ChatColor.GRAY + "普通召回费用：" + ChatColor.WHITE + service.formatMoney(service.normalRecallCost()));
        lore.add(ChatColor.GRAY + "丢失召回费用：" + ChatColor.WHITE + service.formatMoney(service.lostRecallCost()));
        lore.add(ChatColor.GRAY + "召回冷却：" + ChatColor.WHITE + service.recallCooldownSeconds() + " 秒");
        return lore;
    }

    private List<String> databaseLore() {
        File database = store.getDatabaseFile();
        File wal = new File(database.getAbsolutePath() + "-wal");
        File shm = new File(database.getAbsolutePath() + "-shm");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "状态：" + (store.isReady() ? ChatColor.GREEN + "已连接" : ChatColor.RED + "不可用"));
        lore.add(ChatColor.GRAY + "配置状态：" + (store.isUsingConfiguredDatabaseFile() ? ChatColor.GREEN + "已生效" : ChatColor.YELLOW + "配置已变更，重启后生效"));
        lore.add(ChatColor.GRAY + "数据库文件：" + ChatColor.WHITE + database.getName());
        lore.add(ChatColor.GRAY + "主库大小：" + ChatColor.WHITE + formatBytes(database.exists() ? database.length() : 0L));
        lore.add(ChatColor.GRAY + "WAL 日志大小：" + ChatColor.WHITE + formatBytes(wal.exists() ? wal.length() : 0L));
        lore.add(ChatColor.GRAY + "SHM 文件大小：" + ChatColor.WHITE + formatBytes(shm.exists() ? shm.length() : 0L));
        lore.add(ChatColor.GRAY + "当前内存记录：" + ChatColor.WHITE + store.all().size() + " 条");
        lore.add(ChatColor.GRAY + "待写入记录：" + ChatColor.WHITE + store.dirtyCount() + " 条");
        lore.add(ChatColor.GRAY + "待删除同步：" + ChatColor.WHITE + store.pendingDeleteCount() + " 条");
        lore.add(ChatColor.GRAY + "写入队列：" + (store.hasPendingWrites() ? ChatColor.YELLOW + "有待处理" : ChatColor.GREEN + "已同步"));
        lore.add(ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "执行 SQLite 安全检查点");
        lore.add(ChatColor.GRAY + "优化入口：" + ChatColor.WHITE + "右侧紫水晶按钮");
        lore.add(ChatColor.DARK_GRAY + "路径：" + database.getAbsolutePath());
        return lore;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " 字节";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0D;
        int unit = 0;
        while (value >= 1024.0D && unit + 1 < units.length) {
            value /= 1024.0D;
            unit++;
        }
        double rounded = Math.round(value * 10.0D) / 10.0D;
        String text = rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
        return text + " " + units[unit];
    }

    private void runDatabaseCheckpoint(Player player) {
        service.playSound(player, "gui.refresh");
        if (store.checkpoint()) {
            player.sendMessage(service.prefix() + ChatColor.GREEN + "数据库安全检查点已完成，SQLite 缓存日志已尽量写入主数据库。 ");
            service.playSound(player, "success");
        } else {
            player.sendMessage(service.prefix() + ChatColor.RED + "数据库安全检查点未完成，请查看控制台中文日志并稍后重试。 ");
            service.playSound(player, "error");
        }
    }

    private void runDatabaseOptimize(Player player) {
        service.playSound(player, "gui.refresh");
        if (store.optimize()) {
            player.sendMessage(service.prefix() + ChatColor.GREEN + "数据库优化已完成：增量保存、SQLite 优化和 WAL 检查点均已执行。 ");
            service.playSound(player, "success");
        } else {
            player.sendMessage(service.prefix() + ChatColor.RED + "数据库优化未完成，请查看控制台中文日志并稍后重试。 ");
            service.playSound(player, "error");
        }
    }

    private void runAdminMaintenance(Player player) {
        service.playSound(player, "gui.backup");
        player.sendMessage(service.prefix() + ChatColor.YELLOW + "开始执行一键维护：分批全服扫描 → 数据库优化 → 数据库检查点 → 手动备份。 ");
        if (service.startIncrementalFullScan(player, () -> {
            store.optimize();
            if (service.createManualBackup(player)) {
                player.sendMessage(service.prefix() + ChatColor.GREEN + "一键维护已完成：扫描、数据库优化、落盘和备份均已执行。 ");
            } else {
                player.sendMessage(service.prefix() + ChatColor.RED + "一键维护未完全完成：扫描已执行，但备份或数据库检查点失败，请查看控制台日志。 ");
            }
        })) {
            player.closeInventory();
        }
    }

    private void restoreBackup(Player player, File backupFile) {
        if (!isRestorableBackupFile(backupFile)) {
            player.sendMessage(service.prefix() + ChatColor.RED + "还原失败：只能还原插件 Backup 子文件夹中的 binder.db，或旧 backups 目录中的 binder-*.db 文件。 ");
            service.playSound(player, "error");
            return;
        }
        player.sendMessage(service.prefix() + ChatColor.YELLOW + "开始还原数据库：正在先创建还原前备份，请勿重复点击。 ");
        if (!service.createPreRestoreBackup(player)) {
            player.sendMessage(service.prefix() + ChatColor.RED + "还原已取消：还原前备份创建失败，请查看控制台日志。 ");
            service.playSound(player, "error");
            return;
        }
        player.sendMessage(service.prefix() + ChatColor.YELLOW + "还原前备份已完成，正在替换并重新加载数据库。 ");
        if (store.restoreFromBackup(backupFile)) {
            player.sendMessage(service.prefix() + ChatColor.GREEN + "数据库还原完成，已重新加载 " + store.all().size() + " 条绑定记录。 ");
            player.sendMessage(service.prefix() + ChatColor.YELLOW + "建议立即执行一次全服安全扫描，确认线上真实物品位置与还原记录一致。 ");
            service.playSound(player, "success");
            openAdminStats(player);
        } else {
            player.sendMessage(service.prefix() + ChatColor.RED + "数据库还原失败，请查看控制台日志。若数据库不可用，请使用还原前备份或重启服务器处理。 ");
            service.playSound(player, "error");
        }
    }

    private List<String> playerAdviceLore(StatisticSnapshot stats, int unlocked) {
        List<String> lore = new ArrayList<>();
        if (stats.total() == 0) {
            lore.add(ChatColor.GRAY + "你还没有绑定物。");
            lore.add(ChatColor.GRAY + "可以返回主菜单绑定主手物品。");
            return lore;
        }
        if (stats.lost() > 0) {
            lore.add(ChatColor.RED + "有 " + stats.lost() + " 个绑定物显示为已丢失。");
            lore.add(ChatColor.GRAY + "建议进入绑定物库执行安全召回。");
        }
        if (stats.dropped() > 0) {
            lore.add(ChatColor.YELLOW + "有 " + stats.dropped() + " 个绑定物处于掉落物状态。");
            lore.add(ChatColor.GRAY + "建议尽快拾取或召回。");
        }
        if (unlocked > 0) {
            lore.add(ChatColor.YELLOW + "有 " + unlocked + " 个绑定物未开启不可离包。");
            lore.add(ChatColor.GRAY + "重要物品可在详情页开启不可离包。");
        }
        if (lore.isEmpty()) {
            lore.add(ChatColor.GREEN + "当前绑定物状态良好。");
            lore.add(ChatColor.GRAY + "建议定期打开此页面执行体检。");
        }
        return lore;
    }

    private void sendPlayerSummary(Player player, List<BindingRecord> records) {
        StatisticSnapshot stats = statistics(records, 0);
        player.sendMessage(service.prefix() + ChatColor.YELLOW + "你的绑定体检摘要：");
        player.sendMessage(ChatColor.GRAY + "总数：" + ChatColor.WHITE + stats.total()
                + ChatColor.GRAY + "，不可离包：" + ChatColor.WHITE + stats.locked()
                + ChatColor.GRAY + "，已丢失：" + ChatColor.WHITE + stats.lost()
                + ChatColor.GRAY + "，掉落物：" + ChatColor.WHITE + stats.dropped());
        player.sendMessage(ChatColor.GRAY + "玩家背包：" + ChatColor.WHITE + stats.player()
                + ChatColor.GRAY + "，临时界面：" + ChatColor.WHITE + stats.temporary()
                + ChatColor.GRAY + "，容器/实体容器：" + ChatColor.WHITE + stats.container());
    }

    private void sendProblemRecords(Player player, List<BindingRecord> records) {
        List<BindingRecord> problems = new ArrayList<>();
        for (BindingRecord record : records) {
            if (needsAttention(record)) {
                problems.add(record);
            }
        }
        problems.sort(Comparator.comparingInt(this::attentionPriority));
        if (problems.isEmpty()) {
            player.sendMessage(service.prefix() + ChatColor.GREEN + "未发现需要特别关注的绑定物。 ");
            return;
        }
        player.sendMessage(service.prefix() + ChatColor.YELLOW + "需要关注的绑定物清单（最多显示 10 件）：");
        int limit = Math.min(10, problems.size());
        for (int i = 0; i < limit; i++) {
            BindingRecord record = problems.get(i);
            player.sendMessage(ChatColor.GRAY + "#" + store.ownerIndex(record)
                    + ChatColor.WHITE + " " + problemReason(record)
                    + ChatColor.GRAY + "，位置：" + ChatColor.WHITE + record.getLocation().describe());
        }
        if (problems.size() > limit) {
            player.sendMessage(ChatColor.GRAY + "还有 " + ChatColor.WHITE + (problems.size() - limit)
                    + ChatColor.GRAY + " 件未显示，请打开绑定物库分页查看。");
        }
    }

    private BindingRecord firstAttentionRecord(List<BindingRecord> records) {
        BindingRecord best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (BindingRecord record : records) {
            if (!needsAttention(record)) {
                continue;
            }
            int priority = attentionPriority(record);
            if (priority < bestPriority) {
                best = record;
                bestPriority = priority;
            }
        }
        return best;
    }

    private boolean needsAttention(BindingRecord record) {
        BindingLocation location = record.getLocation();
        if (!record.isLocked()) {
            return true;
        }
        return switch (location.getType()) {
            case LOST, DROPPED, TEMPORARY -> true;
            case CONTAINER -> false;
            case PLAYER -> location.getHolderUuid() != null && !location.getHolderUuid().equals(record.getOwnerUuid());
        };
    }

    private int attentionPriority(BindingRecord record) {
        BindingLocation location = record.getLocation();
        if (location.getType() == BindingLocation.Type.LOST) {
            return 0;
        }
        if (location.getType() == BindingLocation.Type.PLAYER
                && location.getHolderUuid() != null
                && !location.getHolderUuid().equals(record.getOwnerUuid())) {
            return 1;
        }
        if (location.getType() == BindingLocation.Type.DROPPED) {
            return 2;
        }
        if (location.getType() == BindingLocation.Type.TEMPORARY) {
            return 3;
        }
        return record.isLocked() ? 9 : 4;
    }

    private String problemReason(BindingRecord record) {
        BindingLocation location = record.getLocation();
        if (location.getType() == BindingLocation.Type.LOST) {
            return ChatColor.RED + "已丢失，建议进入确认页召回";
        }
        if (location.getType() == BindingLocation.Type.DROPPED) {
            return ChatColor.YELLOW + "处于掉落物状态，建议尽快拾取或召回";
        }
        if (location.getType() == BindingLocation.Type.TEMPORARY) {
            return ChatColor.YELLOW + "位于临时界面，建议等待关闭后刷新或确认召回";
        }
        if (location.getType() == BindingLocation.Type.PLAYER
                && location.getHolderUuid() != null
                && !location.getHolderUuid().equals(record.getOwnerUuid())) {
            return ChatColor.YELLOW + "由其他玩家持有，召回需要二次确认";
        }
        if (!record.isLocked()) {
            return ChatColor.YELLOW + "未开启不可离包，重要物品建议开启";
        }
        return ChatColor.GREEN + "状态正常";
    }

    private boolean hasUnlockedBinding(UUID ownerUuid) {
        for (BindingRecord record : store.byOwner(ownerUuid)) {
            if (!record.isLocked()) {
                return true;
            }
        }
        return false;
    }

    private boolean isTopClick(InventoryClickEvent event) {
        return event.getRawSlot() >= 0 && event.getRawSlot() < event.getView().getTopInventory().getSize();
    }

    private boolean isFastConfirmClick(Player player) {
        long now = System.currentTimeMillis();
        long last = confirmDebounce.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 800L) {
            player.sendMessage(service.prefix() + ChatColor.YELLOW + "操作过快，请稍等一下再确认。 ");
            service.playSound(player, "gui.disabled");
            return true;
        }
        confirmDebounce.put(player.getUniqueId(), now);
        return false;
    }

    private boolean ensureAdmin(Player player) {
        if (player.hasPermission("bind.admin")) {
            return true;
        }
        player.sendMessage(service.prefix() + ChatColor.RED + "你没有管理员权限。 ");
        service.playSound(player, "error");
        openMain(player);
        return false;
    }

    private ItemStack backupDisplay(BackupEntry entry, int index) {
        Material material = index == 1 ? Material.ENCHANTED_BOOK : Material.BOOK;
        return named(material, ChatColor.LIGHT_PURPLE + "◇ 数据库备份 #" + index, List.of(
                ChatColor.GRAY + "备份文件夹：" + ChatColor.WHITE + entry.folderName(),
                ChatColor.GRAY + "数据库文件：" + ChatColor.WHITE + entry.file().getName(),
                ChatColor.GRAY + "大小：" + ChatColor.WHITE + formatBytes(entry.size()),
                ChatColor.GRAY + "备份时间：" + ChatColor.WHITE + formatBackupTime(entry.backupTime()),
                ChatColor.GRAY + "数据库实际时间：" + ChatColor.WHITE + formatBackupTime(entry.databaseTime()),
                ChatColor.GRAY + "距离现在：" + ChatColor.WHITE + formatAge(entry.lastModified()),
                ChatColor.GRAY + "来源：" + ChatColor.WHITE + entry.source(),
                ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━",
                ChatColor.YELLOW + "点击：" + ChatColor.WHITE + "选择此备份并进入二次确认",
                ChatColor.RED + "还原前会自动再创建一次完整备份。"
        ));
    }

    private List<BackupEntry> backupEntries() {
        List<BackupEntry> entries = new ArrayList<>();
        File root = new File(plugin.getDataFolder(), "Backup");
        File[] folders = root.listFiles(File::isDirectory);
        if (folders != null) {
            Arrays.sort(folders, Comparator.comparingLong(File::lastModified).reversed());
            for (File folder : folders) {
                File database = new File(folder, "binder.db");
                if (database.isFile()) {
                    entries.add(backupEntry(database, folder.getName(), "新版文件夹备份"));
                }
            }
        }
        File legacyFolder = new File(plugin.getDataFolder(), "backups");
        File[] legacyFiles = legacyFolder.listFiles((dir, name) -> name.startsWith("binder-") && name.endsWith(".db"));
        if (legacyFiles != null) {
            Arrays.sort(legacyFiles, Comparator.comparingLong(File::lastModified).reversed());
            for (File file : legacyFiles) {
                if (file.isFile()) {
                    entries.add(backupEntry(file, "旧版散落备份", "旧版兼容备份"));
                }
            }
        }
        entries.sort(Comparator.comparingLong(BackupEntry::lastModified).reversed());
        return entries;
    }

    private BackupEntry backupEntry(File file) {
        String folderName = file.getParentFile() == null ? "未知文件夹" : file.getParentFile().getName();
        return backupEntry(file, folderName, "备份");
    }

    private BackupEntry backupEntry(File file, String folderName, String source) {
        long backupTime = readBackupInfoTime(file.getParentFile(), "备份时间戳", file.lastModified());
        long databaseTime = readBackupInfoTime(file.getParentFile(), "数据库实际时间戳", file.lastModified());
        return new BackupEntry(file, folderName, source, file.length(), backupTime, databaseTime, file.lastModified());
    }

    private long readBackupInfoTime(File folder, String key, long fallback) {
        if (folder == null) {
            return fallback;
        }
        File infoFile = new File(folder, "备份信息.yml");
        if (!infoFile.exists()) {
            return fallback;
        }
        YamlConfiguration info = YamlConfiguration.loadConfiguration(infoFile);
        return info.getLong(key, fallback);
    }

    private String formatBackupTime(long timestamp) {
        if (timestamp <= 0L) {
            return "未知";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp));
    }

    private boolean isRestorableBackupFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        try {
            File folder = new File(plugin.getDataFolder(), "Backup").getCanonicalFile();
            File legacyFolder = new File(plugin.getDataFolder(), "backups").getCanonicalFile();
            File canonical = file.getCanonicalFile();
            File parent = canonical.getParentFile();
            File grandParent = parent == null ? null : parent.getParentFile();
            boolean newFolderBackup = parent != null
                    && grandParent != null
                    && grandParent.equals(folder)
                    && canonical.getName().equals("binder.db");
            boolean legacyFlatBackup = parent != null
                    && parent.equals(legacyFolder)
                    && canonical.getName().startsWith("binder-")
                    && canonical.getName().endsWith(".db");
            return newFolderBackup || legacyFlatBackup;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<PlayerEntry> knownPlayers() {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.put(player.getUniqueId(), player.getName());
        }
        for (BindingRecord record : store.all()) {
            names.putIfAbsent(record.getOwnerUuid(), record.getOwnerName());
        }
        List<PlayerEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            entries.add(new PlayerEntry(entry.getKey(), entry.getValue() == null ? entry.getKey().toString() : entry.getValue()));
        }
        entries.sort(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private List<QuarantineEntry> quarantineEntries() {
        File file = new File(plugin.getDataFolder(), "quarantine.yml");
        if (!file.exists()) {
            return List.of();
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = configuration.getConfigurationSection("items");
        if (root == null) {
            return List.of();
        }
        List<QuarantineEntry> entries = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section != null) {
                entries.add(new QuarantineEntry(
                        section.getItemStack("item"),
                        section.getString("binding-id", "未知"),
                        section.getString("duplicate-location", "未知"),
                        section.getLong("created-at", 0L)
                ));
            }
        }
        entries.sort(Comparator.comparingLong(QuarantineEntry::createdAt).reversed());
        return entries;
    }

    private StatisticSnapshot statistics() {
        return statistics(store.all(), quarantineEntries().size());
    }

    private StatisticSnapshot statistics(List<BindingRecord> records, int quarantine) {
        Set<UUID> owners = new HashSet<>();
        int locked = 0;
        int lost = 0;
        int player = 0;
        int temporary = 0;
        int container = 0;
        int dropped = 0;
        for (BindingRecord record : records) {
            owners.add(record.getOwnerUuid());
            if (record.isLocked()) {
                locked++;
            }
            switch (record.getLocation().getType()) {
                case PLAYER -> player++;
                case TEMPORARY -> temporary++;
                case CONTAINER -> container++;
                case DROPPED -> dropped++;
                case LOST -> lost++;
            }
        }
        return new StatisticSnapshot(records.size(), owners.size(), locked, lost, player, temporary, container, dropped, quarantine);
    }

    private record PlayerEntry(UUID uuid, String name) {
    }

    private record QuarantineEntry(ItemStack item, String bindingId, String location, long createdAt) {
    }

    private record BackupEntry(File file, String folderName, String source, long size, long backupTime, long databaseTime, long lastModified) {
    }

    private record StatisticSnapshot(int total, int owners, int locked, int lost, int player, int temporary, int container, int dropped, int quarantine) {
    }

    private enum GuiAction {
        UNBIND,
        LOCK_ALL
    }

    private static final class MainHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class PlayerStatsHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class HelpHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class AdminMenuHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class AdminStatsHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ListHolder implements InventoryHolder {
        private final UUID owner;
        private final String ownerName;
        private final UUID[] recordIds = new UUID[PAGE_SIZE];
        private final int page;
        private final int totalPages;
        private final boolean admin;
        private Inventory inventory;

        private ListHolder(UUID owner, String ownerName, int page, int totalPages, boolean admin) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.page = page;
            this.totalPages = totalPages;
            this.admin = admin;
        }

        private void setRecord(int slot, UUID id) {
            if (slot >= 0 && slot < recordIds.length) {
                recordIds[slot] = id;
            }
        }

        private UUID getRecord(int slot) {
            if (slot < 0 || slot >= recordIds.length) {
                return null;
            }
            return recordIds[slot];
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class DetailHolder implements InventoryHolder {
        private final UUID recordId;
        private final boolean admin;
        private final UUID targetUuid;
        private final String targetName;
        private Inventory inventory;

        private DetailHolder(UUID recordId, boolean admin, UUID targetUuid, String targetName) {
            this.recordId = recordId;
            this.admin = admin;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private final UUID recordId;
        private final BindingService.ConfirmType type;
        private Inventory inventory;

        private ConfirmHolder(UUID recordId, BindingService.ConfirmType type) {
            this.recordId = recordId;
            this.type = type;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ActionConfirmHolder implements InventoryHolder {
        private final UUID recordId;
        private final GuiAction action;
        private final boolean admin;
        private final UUID targetUuid;
        private final String targetName;
        private Inventory inventory;

        private ActionConfirmHolder(UUID recordId, GuiAction action, boolean admin, UUID targetUuid, String targetName) {
            this.recordId = recordId;
            this.action = action;
            this.admin = admin;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class AdminPlayersHolder implements InventoryHolder {
        private final PlayerEntry[] players = new PlayerEntry[PAGE_SIZE];
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private AdminPlayersHolder(int page, int totalPages) {
            this.page = page;
            this.totalPages = totalPages;
        }

        private void setPlayer(int slot, PlayerEntry player) {
            if (slot >= 0 && slot < players.length) {
                players[slot] = player;
            }
        }

        private PlayerEntry getPlayer(int slot) {
            if (slot < 0 || slot >= players.length) {
                return null;
            }
            return players[slot];
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OwnerSelectHolder implements InventoryHolder {
        private final UUID recordId;
        private final PlayerEntry[] players = new PlayerEntry[PAGE_SIZE];
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private OwnerSelectHolder(UUID recordId, int page, int totalPages) {
            this.recordId = recordId;
            this.page = page;
            this.totalPages = totalPages;
        }

        private void setPlayer(int slot, PlayerEntry player) {
            if (slot >= 0 && slot < players.length) {
                players[slot] = player;
            }
        }

        private PlayerEntry getPlayer(int slot) {
            if (slot < 0 || slot >= players.length) {
                return null;
            }
            return players[slot];
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class QuarantineHolder implements InventoryHolder {
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private QuarantineHolder(int page, int totalPages) {
            this.page = page;
            this.totalPages = totalPages;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BackupListHolder implements InventoryHolder {
        private final File[] backups = new File[PAGE_SIZE];
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private BackupListHolder(int page, int totalPages) {
            this.page = page;
            this.totalPages = totalPages;
        }

        private void setBackup(int slot, File file) {
            if (slot >= 0 && slot < backups.length) {
                backups[slot] = file;
            }
        }

        private File getBackup(int slot) {
            if (slot < 0 || slot >= backups.length) {
                return null;
            }
            return backups[slot];
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class RestoreConfirmHolder implements InventoryHolder {
        private final File backupFile;
        private Inventory inventory;

        private RestoreConfirmHolder(File backupFile) {
            this.backupFile = backupFile;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
