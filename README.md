# MysticCombat

MysticCombat is a Paper 1.20+ plugin that rolls RPG-style item attributes for crafting, enchanting, and anvil outputs.

## Current status

Core implementation is now production-ready for the requested feature set:

- Config-driven weighted rarity and attribute rolling
- Anti-abuse via PDC marker + debug metadata (`*_rarity`, `*_attribute_ids`)
- Marker compatibility check for both `BYTE` and `STRING` to avoid bypass after `pdc_data_type` changes
- Duplicate-safe runtime key strategy (`duplicate_modifier_mode: UNIQUE|MERGE`) to keep lore and real stats consistent
- Vanilla override fix by restoring default Material modifiers before custom stats
- Wildcard item matching (`*_SWORD`, etc.)
- Duplicate and two-way blacklist conflict handling during roll sessions
- Lore rendering with plugin section replacement to prevent stale/duplicated lore lines
- Trigger listeners for crafting, enchanting, and anvil result slot click
- Versioned merge for `config.yml` and `messages.yml` that preserves user keys (including nested keys)
- `/mysticcombat` + `/mc` commands:
  - `/mc reload`
  - `/mc attribute roll <rarity>`
  - `/mc attribute add <attribute_id> <value> [--force]`
  - `/mc attribute remove <attribute_id> [value]`
  - `/mc attribute list`
  - `/mc attribute clean`

## Rarity roll event API

MysticCombat exposes two official Bukkit events for third-party plugins:

- `PreRarityRollEvent` (cancellable): fired right before rarity selection
- `PostRarityRollEvent` (result event): fired for every terminal outcome (`SUCCESS`, `SKIPPED`, `CANCELLED`)

### Lifecycle

`trigger -> pre event -> roll resolution/apply -> post event`

Trigger source is always provided through `RollTriggerSource` (`CRAFT`, `ENCHANT`, `ANVIL`, `COMMAND`, `API`).

### Mutable vs read-only fields

- `PreRarityRollEvent`
  - Mutable: `forcedRarityId`, cancel flag
  - Read-only: player, item stack reference, source
- `PostRarityRollEvent`
  - Read-only: all fields (`status`, `rarityId`, `appliedStatsCount`, `reason`, player, item, source)

### Behavior contract

- Events are called synchronously on the main thread.
- Rarity override priority follows Bukkit listener priority naturally (last write wins at highest effective priority).
- If a forced rarity is invalid, roll is safely skipped and reason is surfaced as `Unknown forced rarity '<id>'`.
- If no external listeners exist, roll behavior remains unchanged.

### Minimal integration examples

```java
// Block rarity roll for a custom condition.
@EventHandler(priority = EventPriority.HIGH)
public void onPreRoll(PreRarityRollEvent event) {
    if (event.getPlayer() != null && event.getPlayer().hasPermission("myplugin.roll.blocked")) {
        event.setCancelled(true);
    }
}

// Force a specific rarity for selected players.
@EventHandler(priority = EventPriority.HIGH)
public void onPreRollForce(PreRarityRollEvent event) {
    if (event.getPlayer() != null && event.getPlayer().hasPermission("myplugin.roll.force.rare")) {
        event.setForcedRarityId("Rare");
    }
}

// Observe final result for analytics/reward chaining.
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
public void onPostRoll(PostRarityRollEvent event) {
    getLogger().info(
            "Roll status=" + event.getStatus()
                    + ", source=" + event.getSource()
                    + ", rarity=" + event.getRarityId()
                    + ", appliedStats=" + event.getAppliedStatsCount()
                    + ", reason=" + event.getReason()
    );
}
```

## Test coverage

Current tests cover:

- Weighted picker behavior (`GachaManager`)
- Wildcard matcher and value formatter (`Utils`)
- Duplicate prevention and blacklist conflict filtering (`GachaManager` eligibility)
- Marker type fallback protection (`GachaManager`)
- Nested config merge behavior and user-key preservation (`ConfigVersioningService`)
- Event flow contract (`PreRarityRollEvent` and `PostRarityRollEvent`)

## Quick start

```powershell
mvn clean test
mvn clean package
```

The generated plugin jar will be in `target/`.
