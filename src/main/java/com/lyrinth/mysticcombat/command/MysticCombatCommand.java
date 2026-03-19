package com.lyrinth.mysticcombat.command;

import com.lyrinth.mysticcombat.Main;
import com.lyrinth.mysticcombat.api.event.RollTriggerSource;
import com.lyrinth.mysticcombat.config.ConfigManager;
import com.lyrinth.mysticcombat.config.MessagesManager;
import com.lyrinth.mysticcombat.gacha.GachaManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MysticCombatCommand implements CommandExecutor, TabCompleter {

    private final Main plugin;

    public MysticCombatCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(usageRoot(label));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }

        if (!args[0].equalsIgnoreCase("attribute")) {
            sender.sendMessage(usageRoot(label));
            return true;
        }

        if (!hasAdminPermission(sender)) {
            sender.sendMessage(msg("general.no_permission"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("general.player_only"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(msg("command.attribute.usage"));
            return true;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        GachaManager gachaManager = plugin.getGachaManager();

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "roll":
                return handleAttributeRoll(sender, player, heldItem, args);
            case "add":
                return handleAttributeAdd(sender, heldItem, args);
            case "remove":
                return handleAttributeRemove(sender, heldItem, args);
            case "list":
                return handleAttributeList(sender, gachaManager);
            case "clean":
                return handleAttributeClean(sender, heldItem);
            default:
                sender.sendMessage(msg("command.attribute.usage"));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "attribute");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("attribute")) {
            return List.of("roll", "add", "remove", "list", "clean");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("attribute") && args[1].equalsIgnoreCase("roll")) {
            return plugin.getConfigManager().getRarities().keySet().stream().toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("attribute")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            return new ArrayList<>(plugin.getConfigManager().getAttributesPool().keySet());
        }

        if (args.length == 5 && args[0].equalsIgnoreCase("attribute") && args[1].equalsIgnoreCase("add")) {
            return List.of("--force");
        }

        return Collections.emptyList();
    }

    private boolean handleReload(CommandSender sender) {
        String reloadPermission = plugin.getConfigManager().getSettings().reloadPermission();
        if (!sender.hasPermission(reloadPermission)) {
            sender.sendMessage(msg("general.no_permission"));
            return true;
        }

        boolean success = plugin.reloadPlugin();
        sender.sendMessage(success ? msg("command.reload.success") : msg("command.reload.failed"));
        return true;
    }

    private boolean handleAttributeRoll(CommandSender sender, Player player, ItemStack heldItem, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("command.attribute.roll.usage"));
            return true;
        }

        GachaManager.RollResult result = plugin.getGachaManager()
                .rollForItemWithRarity(player, heldItem, RollTriggerSource.COMMAND, args[2]);
        Map<String, String> placeholders = Map.of("message", result.message());
        sender.sendMessage(format(result.rolled() ? "command.attribute.roll.success" : "command.attribute.roll.failed", placeholders));
        return true;
    }

    private boolean handleAttributeAdd(CommandSender sender, ItemStack heldItem, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(msg("command.attribute.add.usage"));
            return true;
        }

        double value;
        try {
            value = Double.parseDouble(args[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(msg("command.attribute.add.invalid_value"));
            return true;
        }

        boolean force = args.length >= 5 && "--force".equalsIgnoreCase(args[4]);
        GachaManager.AdminActionResult result = plugin.getGachaManager().addAttributeToItem(heldItem, args[2], value, force);
        sender.sendMessage(format(result.success() ? "command.attribute.add.success" : "command.attribute.add.failed",
                Map.of("message", result.message())));
        return true;
    }

    private boolean handleAttributeRemove(CommandSender sender, ItemStack heldItem, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("command.attribute.remove.usage"));
            return true;
        }

        Double valueFilter = null;
        if (args.length >= 4) {
            try {
                valueFilter = Double.parseDouble(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(msg("command.attribute.remove.invalid_value"));
                return true;
            }
        }

        GachaManager.AdminActionResult result = plugin.getGachaManager().removeAttributeFromItem(heldItem, args[2], valueFilter);
        sender.sendMessage(format(result.success() ? "command.attribute.remove.success" : "command.attribute.remove.failed",
                Map.of("message", result.message())));
        return true;
    }

    private boolean handleAttributeClean(CommandSender sender, ItemStack heldItem) {
        GachaManager.AdminActionResult result = plugin.getGachaManager().cleanItem(heldItem);
        sender.sendMessage(format(result.success() ? "command.attribute.clean.success" : "command.attribute.clean.failed",
                Map.of("message", result.message())));
        return true;
    }

    private boolean handleAttributeList(CommandSender sender, GachaManager manager) {
        List<ConfigManager.AttributeConfig> attributes = manager.listConfiguredAttributes();
        if (attributes.isEmpty()) {
            sender.sendMessage(msg("command.attribute.list.empty"));
            return true;
        }

        sender.sendMessage(msg("command.attribute.list.header"));
        for (ConfigManager.AttributeConfig attribute : attributes) {
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("id", attribute.id());
            placeholders.put("attribute", attribute.displayAttributeName());
            placeholders.put("type", attribute.attributeType().name());
            placeholders.put("operation", attribute.operation().name());
            placeholders.put("slot", attribute.slotGroup().toString());
            placeholders.put("weight", String.valueOf(attribute.weight()));
            placeholders.put("min", String.valueOf(attribute.minValue()));
            placeholders.put("max", String.valueOf(attribute.maxValue()));
            placeholders.put("rarities", String.join(",", attribute.rarities()));
            placeholders.put("applicable", String.join(",", attribute.applicableItems()));
            sender.sendMessage(format("command.attribute.list.entry", placeholders));
        }
        return true;
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission(plugin.getConfigManager().getSettings().adminPermission());
    }

    private String usageRoot(String label) {
        return "/" + label + " reload | /" + label + " attribute <roll|add|remove|list|clean>";
    }

    private String msg(String path) {
        MessagesManager messages = plugin.getMessagesManager();
        return messages == null ? path : messages.get(path);
    }

    private String format(String path, Map<String, String> placeholders) {
        MessagesManager messages = plugin.getMessagesManager();
        return messages == null ? path : messages.format(path, placeholders);
    }
}
