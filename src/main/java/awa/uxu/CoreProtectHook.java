package awa.uxu;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class CoreProtectHook {
    private final BinderPlugin plugin;
    private Object api;
    private Method performPartialLookup;
    private Method parseResult;
    private Method logContainerTransaction;
    private final Map<UUID, CachedLookup> lookupCache = new ConcurrentHashMap<>();
    private final Set<UUID> queuedLookups = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<LookupRequest> lookupQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean lookupWorkerRunning = new AtomicBoolean();
    private volatile long generation;

    public CoreProtectHook(BinderPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        generation++;
        lookupCache.clear();
        lookupQueue.clear();
        queuedLookups.clear();
        this.api = null;
        this.performPartialLookup = null;
        this.parseResult = null;
        this.logContainerTransaction = null;
        if (!plugin.getConfig().getBoolean("coreprotect.enabled", true)) {
            debugLog("CoreProtect 查询功能已在配置中关闭。");
            return;
        }
        Plugin coreProtectPlugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (coreProtectPlugin == null) {
            debugLog("未检测到 CoreProtect，将仅使用插件自身记录定位绑定物。");
            return;
        }
        try {
            Class<?> coreProtectClass = Class.forName("net.coreprotect.CoreProtect");
            if (!coreProtectClass.isInstance(coreProtectPlugin)) {
                debugLog("检测到 CoreProtect，但无法识别其 API 类型。");
                return;
            }
            Object loadedApi = coreProtectClass.getMethod("getAPI").invoke(coreProtectPlugin);
            if (loadedApi == null) {
                debugLog("CoreProtect API 当前不可用。");
                return;
            }
            boolean enabled = (Boolean) loadedApi.getClass().getMethod("isEnabled").invoke(loadedApi);
            int version = (Integer) loadedApi.getClass().getMethod("APIVersion").invoke(loadedApi);
            if (!enabled || version < 11) {
                debugLog("CoreProtect API 未启用或版本过低，需要 API 11 或更高版本。");
                return;
            }
            this.performPartialLookup = loadedApi.getClass().getMethod(
                    "performPartialLookup",
                    int.class,
                    List.class,
                    List.class,
                    List.class,
                    List.class,
                    List.class,
                    int.class,
                    Location.class,
                    int.class,
                    int.class
            );
            this.parseResult = loadedApi.getClass().getMethod("parseResult", String[].class);
            this.logContainerTransaction = loadedApi.getClass().getMethod("logContainerTransaction", String.class, Location.class);
            this.api = loadedApi;
            debugLog("已接入 CoreProtect API " + version + "，可通过容器记录辅助定位绑定物。");
        } catch (ReflectiveOperationException | LinkageError ex) {
            debugLog("接入 CoreProtect API 失败。", ex);
            this.api = null;
        }
    }

    public boolean isAvailable() {
        return api != null;
    }

    /**
     * Returns only cached results. A missing or expired entry is refreshed on the
     * async lookup worker so a server tick can never enter CoreProtect's SQLite query.
     */
    public List<Location> lookupContainerCandidates(BindingRecord record) {
        if (!isAvailable() || record.getItem() == null || record.getItem().getType().isAir()) {
            if (isAvailable()) {
                debugLog("CoreProtect 容器记录读取跳过：绑定编号 " + shortId(record.getId())
                        + " 的物品快照为空，无法按材料筛选候选记录。");
            }
            return List.of();
        }
        List<QueryOrigin> origins = lookupOrigins(record);
        if (origins.isEmpty()) {
            debugLog("CoreProtect 容器记录读取跳过：绑定编号 " + shortId(record.getId())
                    + " 没有可用查询起点，仍将仅依赖当前已加载世界中的真实 UUID 扫描。");
            return List.of();
        }
        long now = System.currentTimeMillis();
        CachedLookup cached = lookupCache.get(record.getId());
        if (cached == null || cached.expiresAt() <= now) {
            enqueueLookup(record, origins);
        }
        if (cached == null) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>(cached.candidates().size());
        for (Candidate candidate : cached.candidates()) {
            World world = Bukkit.getWorld(candidate.worldName());
            if (world != null) {
                locations.add(new Location(world, candidate.x(), candidate.y(), candidate.z()));
            }
        }
        return locations;
    }

    public boolean isLookupPending(BindingRecord record) {
        return record != null && queuedLookups.contains(record.getId());
    }

    public boolean hasFreshLookup(BindingRecord record) {
        if (record == null || !isAvailable()) {
            return true;
        }
        if (record.getItem() == null || record.getItem().getType().isAir() || lookupOrigins(record).isEmpty()) {
            return true;
        }
        CachedLookup cached = lookupCache.get(record.getId());
        return cached != null && cached.expiresAt() > System.currentTimeMillis();
    }

    public boolean hasCachedCandidates(BindingRecord record) {
        if (record == null) {
            return false;
        }
        CachedLookup cached = lookupCache.get(record.getId());
        return cached != null
                && cached.expiresAt() > System.currentTimeMillis()
                && !cached.candidates().isEmpty();
    }

    public void markCandidateVerified(BindingRecord record, Location location) {
        if (record == null || location == null || location.getWorld() == null) {
            return;
        }
        String worldName = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        lookupCache.computeIfPresent(record.getId(), (id, cached) -> {
            List<Candidate> remaining = cached.candidates().stream()
                    .filter(candidate -> !candidate.matches(worldName, x, y, z))
                    .toList();
            return new CachedLookup(remaining, cached.expiresAt());
        });
    }

    private void enqueueLookup(BindingRecord record, List<QueryOrigin> origins) {
        UUID id = record.getId();
        if (!queuedLookups.add(id)) {
            return;
        }
        int maxQueued = Math.max(1, plugin.getConfig().getInt("coreprotect.max-queued-lookups", 64));
        if (lookupQueue.size() >= maxQueued) {
            queuedLookups.remove(id);
            debugLog("CoreProtect 异步查询队列已满，已跳过绑定编号 " + shortId(id) + " 的本次刷新。");
            return;
        }
        int time = Math.max(60, plugin.getConfig().getInt("coreprotect.lookup-time-seconds", 604800));
        int radius = Math.max(1, plugin.getConfig().getInt("coreprotect.lookup-radius", 128));
        int limit = Math.max(10, plugin.getConfig().getInt("coreprotect.lookup-limit", 200));
        Material material = record.getItem().getType();
        boolean includeRemovals = plugin.getConfig().getBoolean("coreprotect.include-container-removals", true);
        long cacheMillis = Math.max(10L, plugin.getConfig().getLong("coreprotect.lookup-cache-seconds", 120L)) * 1000L;
        lookupQueue.add(new LookupRequest(id, material, List.copyOf(origins), time, radius, limit,
                includeRemovals, cacheMillis, generation, api, performPartialLookup, parseResult));
        startLookupWorker();
    }

    private void startLookupWorker() {
        if (!lookupWorkerRunning.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::drainLookupQueue);
    }

    private void drainLookupQueue() {
        try {
            LookupRequest request;
            while ((request = lookupQueue.poll()) != null) {
                try {
                    List<Candidate> candidates = performLookup(request);
                    if (request.generation() == generation && plugin.isEnabled()) {
                        lookupCache.put(request.id(), new CachedLookup(candidates,
                                System.currentTimeMillis() + request.cacheMillis()));
                    }
                } finally {
                    queuedLookups.remove(request.id());
                }
            }
        } finally {
            lookupWorkerRunning.set(false);
            if (!lookupQueue.isEmpty()) {
                startLookupWorker();
            }
        }
    }

    private List<Candidate> performLookup(LookupRequest request) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int returned = 0;
        try {
            for (QueryOrigin origin : request.origins()) {
                debugLog("正在异步读取 CoreProtect 容器记录：绑定编号 " + shortId(request.id())
                        + "，物品 " + request.material().name()
                        + "，起点 " + formatLocation(origin.location())
                        + "，起点来源：" + origin.reason()
                        + "，半径 " + request.radius()
                        + "，时间范围 " + request.time() + " 秒"
                        + "，动作：" + (request.includeRemovals() ? "容器新增/移除" : "容器新增")
                        + "，上限 " + request.limit() + " 条。CoreProtect 只提供候选位置，必须真实 UUID 命中才会采信。");
                List<Object> restrictBlocks = List.of(request.material());
                List<Integer> actions = new ArrayList<>();
                if (!request.includeRemovals()) {
                    actions.add(1);
                }
                @SuppressWarnings("unchecked")
                List<String[]> lookup = (List<String[]>) request.performPartialLookup().invoke(
                        request.api(),
                        request.time(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        restrictBlocks,
                        Collections.emptyList(),
                        actions,
                        request.radius(),
                        origin.location(),
                        0,
                        request.limit()
                );
                if (lookup == null || lookup.isEmpty()) {
                    debugLog("CoreProtect 本次起点未返回容器候选记录：绑定编号 " + shortId(request.id())
                            + "，起点来源：" + origin.reason() + "。");
                    continue;
                }
                returned += lookup.size();
                debugLog("CoreProtect 本次起点返回 " + lookup.size() + " 条容器记录，开始筛选候选位置。绑定编号 "
                        + shortId(request.id()) + "，起点来源：" + origin.reason() + "。");
                for (String[] raw : lookup) {
                    Object parsed = request.parseResult().invoke(request.api(), (Object) raw);
                    int action = (Integer) parsed.getClass().getMethod("getActionId").invoke(parsed);
                    if (action != 0 && action != 1) {
                        continue;
                    }
                    if (!request.includeRemovals() && action != 1) {
                        continue;
                    }
                    Material type = (Material) parsed.getClass().getMethod("getType").invoke(parsed);
                    if (type != request.material()) {
                        continue;
                    }
                    String worldName = (String) parsed.getClass().getMethod("worldName").invoke(parsed);
                    int x = (Integer) parsed.getClass().getMethod("getX").invoke(parsed);
                    int y = (Integer) parsed.getClass().getMethod("getY").invoke(parsed);
                    int z = (Integer) parsed.getClass().getMethod("getZ").invoke(parsed);
                    Candidate candidate = new Candidate(worldName, x, y, z);
                    candidates.putIfAbsent(worldName + ":" + x + ":" + y + ":" + z, candidate);
                }
            }
            if (returned == 0) {
                debugLog("CoreProtect 容器记录读取完成：所有起点均未返回候选记录。绑定编号 " + shortId(request.id()) + "。");
            }
            debugLog("CoreProtect 容器记录读取完成：筛选出 " + candidates.size()
                    + " 个候选容器；后续仍会扫描真实库存中的绑定 UUID，不会仅凭日志召回或补发。绑定编号 "
                    + shortId(request.id()) + "。");
            return new ArrayList<>(candidates.values());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            debugLog("查询 CoreProtect 容器记录失败。", ex);
            return List.of();
        }
    }

    public void logContainerTransaction(String user, Location location) {
        if (!isAvailable()
                || !plugin.getConfig().getBoolean("coreprotect.log-plugin-transactions", true)
                || location == null) {
            return;
        }
        try {
            logContainerTransaction.invoke(api, user, location);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            debugLog("写入 CoreProtect 容器记录失败。", ex);
        }
    }

    private String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }

    private void debugLog(String message) {
        if (plugin.getConfig().getBoolean("coreprotect.debug-logging", false)) {
            plugin.getLogger().info(message);
        }
    }

    private void debugLog(String message, Throwable throwable) {
        if (plugin.getConfig().getBoolean("coreprotect.debug-logging", false)) {
            plugin.getLogger().log(Level.WARNING, message, throwable);
        }
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "未知位置";
        }
        return location.getWorld().getName() + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private List<QueryOrigin> lookupOrigins(BindingRecord record) {
        Map<String, QueryOrigin> origins = new LinkedHashMap<>();
        BindingLocation location = record.getLocation();
        if (location.getType() == BindingLocation.Type.CONTAINER || location.getType() == BindingLocation.Type.DROPPED) {
            addOrigin(origins, location.toBlockLocation(), "最后记录位置：" + location.describe());
        }
        if (plugin.getConfig().getBoolean("coreprotect.use-online-holder-origin", true) && location.getHolderUuid() != null) {
            Player holder = Bukkit.getPlayer(location.getHolderUuid());
            if (holder != null) {
                addOrigin(origins, holder.getLocation(), "最后记录持有者当前位置：" + holder.getName());
            }
        }
        if (plugin.getConfig().getBoolean("coreprotect.use-online-owner-origin", true)) {
            Player owner = Bukkit.getPlayer(record.getOwnerUuid());
            if (owner != null) {
                addOrigin(origins, owner.getLocation(), "绑定者当前位置：" + owner.getName());
            }
        }
        return new ArrayList<>(origins.values());
    }

    private void addOrigin(Map<String, QueryOrigin> origins, Location location, String reason) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        String key = location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
        origins.putIfAbsent(key, new QueryOrigin(location, reason));
    }

    private record QueryOrigin(Location location, String reason) {
    }

    private record Candidate(String worldName, int x, int y, int z) {
        private boolean matches(String otherWorld, int otherX, int otherY, int otherZ) {
            return worldName.equals(otherWorld) && x == otherX && y == otherY && z == otherZ;
        }
    }

    private record CachedLookup(List<Candidate> candidates, long expiresAt) {
    }

    private record LookupRequest(UUID id, Material material, List<QueryOrigin> origins, int time, int radius,
                                 int limit, boolean includeRemovals, long cacheMillis, long generation,
                                 Object api, Method performPartialLookup, Method parseResult) {
    }
}
