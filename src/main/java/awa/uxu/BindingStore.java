package awa.uxu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class BindingStore {
    private static final String UPSERT_SQL = """
            INSERT INTO bindings (
                id, owner, owner_name, item, locked, created_at, updated_at,
                location_type, world, x, y, z, entity, holder, holder_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                owner = excluded.owner,
                owner_name = excluded.owner_name,
                item = excluded.item,
                locked = excluded.locked,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                location_type = excluded.location_type,
                world = excluded.world,
                x = excluded.x,
                y = excluded.y,
                z = excluded.z,
                entity = excluded.entity,
                holder = excluded.holder,
                holder_name = excluded.holder_name
            """;

    private final BinderPlugin plugin;
    private final File legacyDataFile;
    private final Map<UUID, BindingRecord> records = new LinkedHashMap<>();
    private File databaseFile;
    private Connection connection;
    private boolean ready;
    private boolean pendingFullSave;
    private BukkitTask dirtySaveTask;
    private final Set<UUID> dirtyIds = new HashSet<>();
    private final Set<UUID> deletedIds = new HashSet<>();

    public BindingStore(BinderPlugin plugin) {
        this.plugin = plugin;
        this.legacyDataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        ready = false;
        pendingFullSave = false;
        records.clear();
        try {
            openDatabase();
            createSchema();
            loadFromDatabase();
            ready = true;
            dirtyIds.clear();
            deletedIds.clear();
            if (records.isEmpty() && legacyDataFile.exists()) {
                migrateLegacyYaml();
            }
            plugin.getLogger().info("已从 SQLite 数据库加载 " + records.size() + " 条灵魂绑定记录。");
        } catch (SQLException | IOException ex) {
            records.clear();
            plugin.getLogger().log(Level.SEVERE, "加载绑定数据库失败，插件将无法可靠保存绑定数据。", ex);
        }
    }

    public void save() {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，已拒绝保存以避免覆盖现有数据。");
            return;
        }
        cancelDirtySaveTask();
        try {
            openDatabase();
            runInTransaction(() -> {
                try (Statement delete = connection.createStatement()) {
                    delete.executeUpdate("DELETE FROM bindings");
                }
                try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                    for (BindingRecord record : records.values()) {
                        bindRecord(statement, record);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
            pendingFullSave = false;
            dirtyIds.clear();
            deletedIds.clear();
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存绑定数据库失败。", ex);
            pendingFullSave = true;
        }
    }

    public void saveDirty() {
        scheduleDirtySave();
    }

    public void flushDirty() {
        cancelDirtySaveTask();
        if (pendingFullSave) {
            save();
            return;
        }
        saveDirtyRecords(true);
    }

    public void put(BindingRecord record) {
        records.put(record.getId(), record);
        deletedIds.remove(record.getId());
        dirtyIds.add(record.getId());
        saveOne(record);
    }

    public void markDirty(BindingRecord record) {
        if (record == null) {
            return;
        }
        markDirty(record.getId());
    }

    public void markDirty(UUID id) {
        if (id == null || !records.containsKey(id) || deletedIds.contains(id)) {
            return;
        }
        dirtyIds.add(id);
    }

    public void remove(UUID id) {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，已拒绝删除记录以避免数据不一致：" + id + "。");
            return;
        }
        records.remove(id);
        dirtyIds.remove(id);
        deletedIds.add(id);
        try {
            openDatabase();
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bindings WHERE id = ?")) {
                statement.setString(1, id.toString());
                statement.executeUpdate();
            }
            deletedIds.remove(id);
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "从绑定数据库删除记录失败：" + id + "。", ex);
            pendingFullSave = true;
        }
    }

    public boolean optimize() {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，无法执行数据库优化。");
            return false;
        }
        try {
            openDatabase();
            if (pendingFullSave) {
                save();
            } else {
                saveDirtyRecords(false);
                if (pendingFullSave) {
                    save();
                }
            }
            if (pendingFullSave) {
                plugin.getLogger().warning("数据库优化已取消：当前仍有未完成的完整保存，请先检查上方保存错误。");
                return false;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA optimize");
            }
            if (!checkpoint()) {
                plugin.getLogger().warning("数据库优化未完全完成：SQLite 优化已执行，但 WAL 检查点未完成。");
                return false;
            }
            plugin.getLogger().info("SQLite 数据库优化完成：已执行增量保存、PRAGMA optimize 和 WAL 检查点。");
            return true;
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "执行 SQLite 数据库优化失败。", ex);
            return false;
        }
    }

    public Optional<BindingRecord> find(UUID id) {
        return Optional.ofNullable(records.get(id));
    }

    public List<BindingRecord> all() {
        return new ArrayList<>(records.values());
    }

    public List<BindingRecord> byOwner(UUID ownerUuid) {
        List<BindingRecord> result = new ArrayList<>();
        for (BindingRecord record : records.values()) {
            if (record.getOwnerUuid().equals(ownerUuid)) {
                result.add(record);
            }
        }
        result.sort(Comparator.comparingLong(BindingRecord::getCreatedAt));
        return result;
    }

    public BindingRecord byOwnerIndex(UUID ownerUuid, int index) {
        List<BindingRecord> recordsByOwner = byOwner(ownerUuid);
        if (index < 1 || index > recordsByOwner.size()) {
            return null;
        }
        return recordsByOwner.get(index - 1);
    }

    public int ownerIndex(BindingRecord target) {
        List<BindingRecord> recordsByOwner = byOwner(target.getOwnerUuid());
        for (int i = 0; i < recordsByOwner.size(); i++) {
            if (recordsByOwner.get(i).getId().equals(target.getId())) {
                return i + 1;
            }
        }
        return -1;
    }

    public File getDatabaseFile() {
        return databaseFile == null ? resolveDatabaseFile() : databaseFile;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isUsingConfiguredDatabaseFile() {
        return sameFile(getDatabaseFile(), resolveDatabaseFile());
    }

    public int dirtyCount() {
        return dirtyIds.size();
    }

    public int pendingDeleteCount() {
        return deletedIds.size();
    }

    public boolean hasPendingWrites() {
        return pendingFullSave || !dirtyIds.isEmpty() || !deletedIds.isEmpty();
    }

    public boolean checkpoint() {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，无法执行安全检查点。");
            return false;
        }
        cancelDirtySaveTask();
        try {
            openDatabase();
            if (pendingFullSave) {
                save();
            } else {
                saveDirtyRecords(false);
            }
            if (pendingFullSave) {
                plugin.getLogger().warning("SQLite 安全检查点已取消：当前仍有未完成的数据库保存，请先检查上方错误日志。");
                return false;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (result.next()) {
                    int busy = result.getInt(1);
                    int log = result.getInt(2);
                    int checkpointed = result.getInt(3);
                    if (busy > 0) {
                        plugin.getLogger().warning("SQLite 安全检查点未完全完成，仍有数据库连接繁忙；日志页：" + log + "，已写入页：" + checkpointed + "。");
                        return false;
                    }
                    plugin.getLogger().info("SQLite 安全检查点完成；日志页：" + log + "，已写入页：" + checkpointed + "。");
                }
            }
            return true;
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "执行 SQLite 数据库安全检查点失败。", ex);
            return false;
        }
    }

    public void close() {
        cancelDirtySaveTask();
        if (connection == null) {
            return;
        }
        if (ready) {
            checkpoint();
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "关闭绑定数据库连接失败。", ex);
        } finally {
            connection = null;
        }
    }

    public boolean restoreFromBackup(File backupFile) {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，无法执行备份还原。");
            return false;
        }
        if (backupFile == null || !backupFile.exists() || !backupFile.isFile()) {
            plugin.getLogger().warning("备份还原失败：目标备份文件不存在。");
            return false;
        }
        File target = getDatabaseFile();
        try {
            File targetCanonical = target.getCanonicalFile();
            File backupCanonical = backupFile.getCanonicalFile();
            File backupFolder = new File(plugin.getDataFolder(), "Backup").getCanonicalFile();
            File legacyBackupFolder = new File(plugin.getDataFolder(), "backups").getCanonicalFile();
            File backupParent = backupCanonical.getParentFile();
            File backupGrandParent = backupParent == null ? null : backupParent.getParentFile();
            boolean newFolderBackup = backupParent != null
                    && backupGrandParent != null
                    && backupGrandParent.equals(backupFolder)
                    && backupCanonical.getName().equals("binder.db");
            boolean legacyFlatBackup = backupParent != null
                    && backupParent.equals(legacyBackupFolder)
                    && backupCanonical.getName().startsWith("binder-")
                    && backupCanonical.getName().endsWith(".db");
            if (!newFolderBackup && !legacyFlatBackup) {
                plugin.getLogger().warning("备份还原失败：只允许还原插件 Backup 子文件夹中的 binder.db，或旧 backups 目录中的 binder-*.db 文件。");
                return false;
            }
            save();
            if (!checkpoint()) {
                plugin.getLogger().warning("备份还原失败：还原前数据库检查点未完成。");
                return false;
            }
            closeConnectionOnly();
            deleteSidecarFiles(targetCanonical);
            Files.copy(backupCanonical.toPath(), targetCanonical.toPath(), StandardCopyOption.REPLACE_EXISTING);
            records.clear();
            pendingFullSave = false;
            dirtyIds.clear();
            deletedIds.clear();
            openDatabase();
            createSchema();
            loadFromDatabase();
            ready = true;
            plugin.getLogger().warning("已从备份还原绑定数据库：" + backupCanonical.getName() + "，当前记录数：" + records.size() + "。");
            return true;
        } catch (SQLException | IOException ex) {
            records.clear();
            ready = false;
            plugin.getLogger().log(Level.SEVERE, "备份还原失败，绑定数据库已进入不可用状态，请检查备份文件并重启服务器。", ex);
            return false;
        }
    }

    private void openDatabase() throws SQLException, IOException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IOException("无法创建插件数据目录。");
        }
        if (databaseFile == null) {
            databaseFile = resolveDatabaseFile();
        }
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建数据库目录：" + parent.getAbsolutePath());
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new IOException("无法加载 SQLite JDBC 驱动。", ex);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            int busyTimeout = Math.max(1000, plugin.getConfig().getInt("database.busy-timeout-ms", 5000));
            int walAutocheckpoint = Math.max(0, plugin.getConfig().getInt("database.wal-autocheckpoint-pages", 1000));
            statement.execute("PRAGMA busy_timeout = " + busyTimeout);
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = " + sqlitePragmaName("database.synchronous", "NORMAL"));
            statement.execute("PRAGMA temp_store = " + sqlitePragmaName("database.temp-store", "MEMORY"));
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA wal_autocheckpoint = " + walAutocheckpoint);
        }
    }

    private void closeConnectionOnly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "还原前关闭绑定数据库连接失败。", ex);
        } finally {
            connection = null;
        }
    }

    private void deleteSidecarFiles(File database) throws IOException {
        Files.deleteIfExists(new File(database.getAbsolutePath() + "-wal").toPath());
        Files.deleteIfExists(new File(database.getAbsolutePath() + "-shm").toPath());
    }

    private String sqlitePragmaName(String path, String fallback) {
        String value = plugin.getConfig().getString(path, fallback);
        if (value == null || value.isBlank()) {
            value = fallback;
        }
        String normalized = value.replaceAll("[^A-Za-z_]", "").toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            return fallback.toUpperCase(java.util.Locale.ROOT);
        }
        if (path.endsWith("synchronous")
                && !List.of("OFF", "NORMAL", "FULL", "EXTRA").contains(normalized)) {
            plugin.getLogger().warning("数据库配置 " + path + " 的值无效：" + value + "，已使用默认值 " + fallback + "。");
            return fallback.toUpperCase(java.util.Locale.ROOT);
        }
        if (path.endsWith("temp-store")
                && !List.of("DEFAULT", "FILE", "MEMORY").contains(normalized)) {
            plugin.getLogger().warning("数据库配置 " + path + " 的值无效：" + value + "，已使用默认值 " + fallback + "。");
            return fallback.toUpperCase(java.util.Locale.ROOT);
        }
        return normalized;
    }

    private File resolveDatabaseFile() {
        String configured = plugin.getConfig().getString("database.sqlite-file", "binder.db");
        if (configured == null || configured.isBlank()) {
            configured = "binder.db";
        }
        File file = new File(configured);
        return file.isAbsolute() ? file : new File(plugin.getDataFolder(), configured);
    }

    private boolean sameFile(File first, File second) {
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (IOException ignored) {
            return first.getAbsoluteFile().equals(second.getAbsoluteFile());
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bindings (
                        id TEXT PRIMARY KEY NOT NULL,
                        owner TEXT NOT NULL,
                        owner_name TEXT NOT NULL,
                        item TEXT NOT NULL,
                        locked INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        location_type TEXT NOT NULL,
                        world TEXT,
                        x INTEGER NOT NULL DEFAULT 0,
                        y INTEGER NOT NULL DEFAULT 0,
                        z INTEGER NOT NULL DEFAULT 0,
                        entity TEXT,
                        holder TEXT,
                        holder_name TEXT
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_owner ON bindings(owner)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_location ON bindings(location_type, world, x, y, z)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_updated ON bindings(updated_at)");
        }
    }

    private void loadFromDatabase() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM bindings ORDER BY created_at ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                try {
                    BindingRecord record = readRecord(result);
                    if (record.getItem() != null) {
                        records.put(record.getId(), record);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "无法读取数据库中的绑定记录：" + result.getString("id") + "。", ex);
                }
            }
        }
    }

    private BindingRecord readRecord(ResultSet result) throws SQLException, IOException, ClassNotFoundException {
        UUID id = UUID.fromString(result.getString("id"));
        UUID owner = UUID.fromString(result.getString("owner"));
        String ownerName = result.getString("owner_name");
        ItemStack item = deserializeItem(result.getString("item"));
        boolean locked = result.getInt("locked") == 1;
        long createdAt = result.getLong("created_at");
        long updatedAt = result.getLong("updated_at");
        BindingLocation location = readLocation(result);
        return new BindingRecord(id, owner, ownerName, item, locked, location, createdAt, updatedAt);
    }

    private BindingLocation readLocation(ResultSet result) throws SQLException {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection section = configuration.createSection("location");
        section.set("type", result.getString("location_type"));
        section.set("world", result.getString("world"));
        section.set("x", result.getInt("x"));
        section.set("y", result.getInt("y"));
        section.set("z", result.getInt("z"));
        section.set("entity", result.getString("entity"));
        section.set("holder", result.getString("holder"));
        section.set("holder-name", result.getString("holder_name"));
        return BindingLocation.load(section);
    }

    private void saveOne(BindingRecord record) {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，已拒绝保存单条记录以避免数据不一致：" + record.getId() + "。");
            return;
        }
        try {
            openDatabase();
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                bindRecord(statement, record);
                statement.executeUpdate();
            }
            dirtyIds.remove(record.getId());
            deletedIds.remove(record.getId());
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存单条绑定数据库记录失败：" + record.getId() + "。", ex);
            pendingFullSave = true;
        }
    }

    private void saveDirtyRecords(boolean fallbackToFullSave) {
        saveDirtyRecords(fallbackToFullSave, 0);
    }

    private void saveDirtyRecords(boolean fallbackToFullSave, int maxRecords) {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，已拒绝增量保存以避免覆盖现有数据。");
            return;
        }
        if (dirtyIds.isEmpty() && deletedIds.isEmpty()) {
            return;
        }
        int limit = maxRecords <= 0 ? Integer.MAX_VALUE : maxRecords;
        Set<UUID> deletedSnapshot = snapshotIds(deletedIds, limit, Set.of());
        int remaining = maxRecords <= 0 ? Integer.MAX_VALUE : maxRecords - deletedSnapshot.size();
        Set<UUID> dirtySnapshot = snapshotIds(dirtyIds, remaining, deletedIds);
        if (dirtySnapshot.isEmpty() && deletedSnapshot.isEmpty()) {
            return;
        }
        try {
            openDatabase();
            runInTransaction(() -> {
                if (!deletedSnapshot.isEmpty()) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM bindings WHERE id = ?")) {
                        for (UUID id : deletedSnapshot) {
                            delete.setString(1, id.toString());
                            delete.addBatch();
                        }
                        delete.executeBatch();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                    for (UUID id : dirtySnapshot) {
                        BindingRecord record = records.get(id);
                        if (record == null) {
                            continue;
                        }
                        bindRecord(statement, record);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
            dirtyIds.removeAll(dirtySnapshot);
            deletedIds.removeAll(deletedSnapshot);
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "增量保存绑定数据库失败。", ex);
            if (fallbackToFullSave) {
                plugin.getLogger().warning("将尝试执行完整保存作为增量保存失败后的兜底。");
                pendingFullSave = true;
                save();
            } else {
                pendingFullSave = true;
            }
        }
    }

    private Set<UUID> snapshotIds(Set<UUID> source, int limit, Set<UUID> excluded) {
        Set<UUID> snapshot = new HashSet<>();
        if (source.isEmpty() || limit <= 0) {
            return snapshot;
        }
        for (UUID id : source) {
            if (excluded.contains(id)) {
                continue;
            }
            snapshot.add(id);
            if (snapshot.size() >= limit) {
                break;
            }
        }
        return snapshot;
    }

    private void scheduleDirtySave() {
        if (!ready) {
            plugin.getLogger().warning("绑定数据库尚未成功加载，已拒绝排队保存以避免覆盖现有数据。");
            return;
        }
        if (!hasPendingWrites()) {
            return;
        }
        if (dirtySaveTask != null) {
            return;
        }
        long delayTicks = Math.max(1L, plugin.getConfig().getLong("database.dirty-flush-delay-ticks", 40L));
        dirtySaveTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            dirtySaveTask = null;
            flushQueuedDirtyRecords();
        }, delayTicks);
    }

    private void flushQueuedDirtyRecords() {
        if (!ready || !hasPendingWrites()) {
            return;
        }
        if (pendingFullSave) {
            save();
        } else {
            int maxRecords = Math.max(1, plugin.getConfig().getInt("database.dirty-flush-max-records-per-run", 64));
            saveDirtyRecords(true, maxRecords);
        }
        if (plugin.isEnabled() && hasPendingWrites()) {
            scheduleDirtySave();
        }
    }

    private void cancelDirtySaveTask() {
        if (dirtySaveTask == null) {
            return;
        }
        dirtySaveTask.cancel();
        dirtySaveTask = null;
    }

    private void bindRecord(PreparedStatement statement, BindingRecord record) throws SQLException, IOException {
        BindingLocation location = record.getLocation();
        statement.setString(1, record.getId().toString());
        statement.setString(2, record.getOwnerUuid().toString());
        statement.setString(3, record.getOwnerName());
        statement.setString(4, serializeItem(record.getItem()));
        statement.setInt(5, record.isLocked() ? 1 : 0);
        statement.setLong(6, record.getCreatedAt());
        statement.setLong(7, record.getUpdatedAt());
        statement.setString(8, location.getType().name());
        statement.setString(9, location.getWorld());
        statement.setInt(10, location.getX());
        statement.setInt(11, location.getY());
        statement.setInt(12, location.getZ());
        statement.setString(13, location.getEntityUuid() == null ? null : location.getEntityUuid().toString());
        statement.setString(14, location.getHolderUuid() == null ? null : location.getHolderUuid().toString());
        statement.setString(15, location.getHolderName());
    }

    private void migrateLegacyYaml() {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(legacyDataFile);
        ConfigurationSection root = configuration.getConfigurationSection("bindings");
        if (root == null) {
            return;
        }
        int migrated = 0;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                BindingRecord record = BindingRecord.load(id, root.getConfigurationSection(key));
                if (record.getItem() != null) {
                    records.put(id, record);
                    migrated++;
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "迁移旧 data.yml 绑定记录失败：" + key + "。", ex);
            }
        }
        if (migrated > 0) {
            save();
            plugin.getLogger().info("已将旧 data.yml 中的 " + migrated + " 条绑定记录迁移到 SQLite 数据库；旧文件已保留不删除。");
        }
    }

    private String serializeItem(ItemStack item) throws IOException {
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(byteStream)) {
            output.writeObject(item);
            return Base64.getEncoder().encodeToString(byteStream.toByteArray());
        }
    }

    private ItemStack deserializeItem(String value) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(value);
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object object = input.readObject();
            return object instanceof ItemStack item ? item : null;
        }
    }

    private void rollbackQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 已在外层输出中文错误日志。
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException, IOException;
    }

    private void runInTransaction(SqlRunnable action) throws SQLException, IOException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (SQLException | IOException | RuntimeException ex) {
            rollbackQuietly();
            throw ex;
        } finally {
            restoreAutoCommit(previousAutoCommit);
        }
    }

    private void restoreAutoCommit(boolean autoCommit) {
        if (connection == null) {
            return;
        }
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "恢复数据库自动提交状态失败。", ex);
        }
    }
}
