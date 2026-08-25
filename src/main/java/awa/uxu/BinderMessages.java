package awa.uxu;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BinderMessages {
    private final BinderPlugin plugin;
    private File file;
    private YamlConfiguration configuration;

    public BinderMessages(BinderPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，消息配置将使用内置默认值。");
        }
        file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        configuration = YamlConfiguration.loadConfiguration(file);
    }

    public String text(String path, String fallback) {
        String value = configuration == null ? fallback : configuration.getString(path, fallback);
        return color(value == null ? fallback : value);
    }

    public List<String> list(String path, List<String> fallback) {
        List<String> values = configuration == null ? fallback : configuration.getStringList(path);
        if (values == null || values.isEmpty()) {
            values = fallback;
        }
        List<String> colored = new ArrayList<>();
        for (String value : values) {
            colored.add(color(value));
        }
        return colored;
    }

    public String prefix() {
        return text("prefix", "&6[灵魂绑定] &r");
    }

    public String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
