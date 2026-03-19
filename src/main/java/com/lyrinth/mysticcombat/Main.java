package com.lyrinth.mysticcombat;

import com.lyrinth.mysticcombat.command.MysticCombatCommand;
import com.lyrinth.mysticcombat.config.ConfigManager;
import com.lyrinth.mysticcombat.config.ConfigVersioningService;
import com.lyrinth.mysticcombat.config.MessagesManager;
import com.lyrinth.mysticcombat.custom.CustomAttributeManager;
import com.lyrinth.mysticcombat.gacha.GachaManager;
import com.lyrinth.mysticcombat.listener.CustomAttributeCombatListener;
import com.lyrinth.mysticcombat.listener.GachaListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private GachaManager gachaManager;
    private CustomAttributeManager customAttributeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!ensureVersionedFiles()) {
            getLogger().severe("Could not prepare config/messages files. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reloadConfig();

        this.configManager = new ConfigManager(this);
        if (!this.configManager.load()) {
            getLogger().severe("Config validation failed. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.messagesManager = new MessagesManager(this);
        if (!this.messagesManager.load()) {
            getLogger().severe("Could not load messages.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.customAttributeManager = new CustomAttributeManager(this);
        if (!this.customAttributeManager.load()) {
            getLogger().severe("Could not load custom_attributes.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.gachaManager = new GachaManager(this, this.configManager, this.customAttributeManager);

        // Register event listeners after all core managers are initialized.
        getServer().getPluginManager().registerEvents(new GachaListener(this), this);
        getServer().getPluginManager().registerEvents(new CustomAttributeCombatListener(this), this);

        // Register root command and tab completion.
        PluginCommand command = getCommand("mysticcombat");
        if (command != null) {
            MysticCombatCommand executor = new MysticCombatCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'mysticcombat' is missing in plugin.yml");
        }

        getLogger().info("MysticCombat enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MysticCombat disabled.");
    }

    public boolean reloadPlugin() {
        if (!ensureVersionedFiles()) {
            getLogger().warning("Reload aborted because versioned file merge failed.");
            return false;
        }

        reloadConfig();

        // Re-parse config and rebuild managers so runtime behavior stays consistent.
        ConfigManager newConfigManager = new ConfigManager(this);
        if (!newConfigManager.load()) {
            getLogger().warning("Reload aborted because config is invalid.");
            return false;
        }

        MessagesManager newMessagesManager = new MessagesManager(this);
        if (!newMessagesManager.load()) {
            getLogger().warning("Reload aborted because messages.yml is invalid.");
            return false;
        }

        CustomAttributeManager newCustomAttributeManager = new CustomAttributeManager(this);
        if (!newCustomAttributeManager.load()) {
            getLogger().warning("Reload aborted because custom_attributes.yml is invalid.");
            return false;
        }

        this.configManager = newConfigManager;
        this.messagesManager = newMessagesManager;
        this.customAttributeManager = newCustomAttributeManager;
        this.gachaManager = new GachaManager(this, this.configManager, this.customAttributeManager);
        return true;
    }

    private boolean ensureVersionedFiles() {
        ConfigVersioningService versioningService = new ConfigVersioningService(this);
        boolean configMerged = versioningService.ensureMerged("config.yml", "config_version");
        boolean messagesMerged = versioningService.ensureMerged("messages.yml", "messages_version");
        boolean customMerged = versioningService.ensureMerged("custom_attributes.yml", "custom_attributes_version");
        return configMerged && messagesMerged && customMerged;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessagesManager getMessagesManager() {
        return messagesManager;
    }

    public GachaManager getGachaManager() {
        return gachaManager;
    }

    public CustomAttributeManager getCustomAttributeManager() {
        return customAttributeManager;
    }
}
