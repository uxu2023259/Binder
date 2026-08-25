package awa.uxu;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class BinderPlugin extends JavaPlugin {
    private BindingStore store;
    private BindingService service;
    private BinderGui gui;
    private CoreProtectHook coreProtectHook;
    private BinderMessages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("正在加载 Binder 灵魂绑定插件，请稍候……");
        messages = new BinderMessages(this);
        messages.load();
        store = new BindingStore(this);
        store.load();
        if (!store.isReady()) {
            getLogger().severe("绑定数据库不可用，为避免数据丢失，插件已自动停用。请检查数据库配置、文件权限和 SQLite 驱动。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        service = new BindingService(this, store, messages);
        Economy economy = loadEconomy();
        service.setEconomy(economy);
        coreProtectHook = new CoreProtectHook(this);
        coreProtectHook.load();
        service.setCoreProtectHook(coreProtectHook);
        gui = new BinderGui(this, service, store);
        service.setGui(gui);

        BindCommand bindCommand = new BindCommand(this, service, store, gui);
        PluginCommand command = getCommand("bind");
        if (command == null) {
            getLogger().severe("plugin.yml 缺少 /bind 命令，插件已自动停用。 ");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(bindCommand);
        command.setTabCompleter(bindCommand);

        Bukkit.getPluginManager().registerEvents(new BinderListener(this, service, gui), this);
        long interval = Math.max(100L, getConfig().getLong("binding.scan-interval-ticks", 1200L));
        int recordsPerRun = Math.max(1, getConfig().getInt("binding.scan-records-per-run", 64));
        long scanBudgetMillis = Math.max(1L, getConfig().getLong("binding.scan-time-budget-ms", 10L));
        Bukkit.getScheduler().runTaskTimer(this, () -> service.scanAndCleanAutomatic(), 100L, interval);
        scheduleDatabaseBackups();
        logStartupBanner(economy != null, coreProtectHook.isAvailable(), interval, recordsPerRun, scanBudgetMillis);
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.flushDirty();
            store.close();
        }
        logShutdownBanner();
    }

    private void logStartupBanner(boolean economyAvailable, boolean coreProtectAvailable, long interval, int recordsPerRun, long scanBudgetMillis) {
        getLogger().info("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        getLogger().info("┃ Binder 灵魂绑定 已成功启用");
        getLogger().info("┃ 插件版本：" + getDescription().getVersion());
        getLogger().info("┃ 兼容目标：Paper / Leaves 1.20.4 - 1.21.11");
        getLogger().info("┃ 已加载绑定记录：" + store.all().size() + " 条");
        getLogger().info("┃ 玩家绑定上限：" + getConfig().getInt("binding.max-per-player", 27));
        getLogger().info("┃ 自动轻量扫描：" + interval + " tick，每轮最多 " + recordsPerRun + " 条 / " + scanBudgetMillis + " 毫秒");
        getLogger().info("┃ Vault 经济：" + (economyAvailable ? "已接入" : "未接入"));
        debugCoreProtectLog("CoreProtect 辅助定位：" + (coreProtectAvailable ? "已接入" : "未接入"));
        debugCoreProtectLog("CoreProtect 深度扫描：" + (getConfig().getBoolean("coreprotect.use-in-auto-scan", true) ? "已开启" : "已关闭"));
        getLogger().info("┃ 绑定物提醒：" + (getConfig().getBoolean("alerts.enabled", true) ? "已开启" : "已关闭"));
        getLogger().info("┃ 数据库定时备份：" + (getConfig().getBoolean("backup.enabled", true) && getConfig().getBoolean("backup.schedule.enabled", true) ? "已开启" : "已关闭"));
        getLogger().info("┃ 数据库事件备份：" + (getConfig().getBoolean("backup.enabled", true) && getConfig().getBoolean("backup.event.enabled", true) ? "已开启" : "已关闭"));
        getLogger().info("┃ 主命令：/bind  别名：/soulbind、/sbind");
        getLogger().info("┃ 权限节点：bind.use / bind.admin");
        getLogger().info("┃ 安全机制：先移除真实原物品，再发还；重复 UUID 自动隔离");
        getLogger().info("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void logShutdownBanner() {
        int saved = store == null ? 0 : store.all().size();
        getLogger().info("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        getLogger().info("┃ Binder 灵魂绑定 已安全停用");
        getLogger().info("┃ 已保存绑定记录：" + saved + " 条");
        getLogger().info(store != null && store.isReady() ? "┃ 绑定数据库已安全写入，感谢使用。" : "┃ 绑定数据库未完成写入，请检查上方错误日志。");
        getLogger().info("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void debugCoreProtectLog(String message) {
        if (getConfig().getBoolean("coreprotect.debug-logging", false)) {
            getLogger().info(message);
        }
    }

    private Economy loadEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("未检测到 Vault，付费召回相关功能将不可用。 ");
            return null;
        }
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().warning("Vault 未提供经济服务，付费召回相关功能将不可用。 ");
            return null;
        }
        getLogger().info("已接入 Vault 经济服务。 ");
        return provider.getProvider();
    }

    public void reloadBinder() {
        reloadConfig();
        if (store != null && !store.isUsingConfiguredDatabaseFile()) {
            getLogger().warning("检测到 database.sqlite-file 已修改；当前运行中的数据库连接仍使用旧文件。请重启服务器或插件后再切换数据库文件，避免运行中误切数据源。");
        }
        if (messages != null) {
            messages.load();
        }
        if (service != null) {
            service.setEconomy(loadEconomy());
            service.clearMaterialCache();
            service.loadAlertPreferences();
        }
        if (coreProtectHook != null) {
            coreProtectHook.load();
        }
    }

    private void scheduleDatabaseBackups() {
        if (!getConfig().getBoolean("backup.enabled", true)
                || !getConfig().getBoolean("backup.schedule.enabled", true)) {
            getLogger().info("数据库定时备份未开启。");
            return;
        }
        long intervalMinutes = Math.max(1L, getConfig().getLong("backup.schedule.interval-minutes", 360L));
        long initialDelayMinutes = Math.max(1L, getConfig().getLong("backup.schedule.initial-delay-minutes", 10L));
        long intervalTicks = intervalMinutes * 60L * 20L;
        long initialDelayTicks = initialDelayMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (service != null) {
                service.createAutomaticBackup("定时备份", getConfig().getBoolean("backup.schedule.include-optional-files", true));
            }
        }, initialDelayTicks, intervalTicks);
        getLogger().info("数据库定时备份已开启：首次延迟 " + initialDelayMinutes + " 分钟，间隔 " + intervalMinutes + " 分钟。");
    }

    public BindingService getBindingService() {
        return service;
    }

    public BinderMessages getMessages() {
        return messages;
    }
}
