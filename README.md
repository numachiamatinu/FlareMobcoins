<div align="center">

# FlareMobcoins

**MobCoins for Minecraft servers: players earn coins by killing mobs and spend them in shops and rotating shops.**

![Minecraft](https://img.shields.io/badge/Minecraft-1.8.8%20→%2026.x-f7b733)
![Platforms](https://img.shields.io/badge/Spigot%20|%20Paper%20|%20Purpur-supported-f7c948)
![Java](https://img.shields.io/badge/Java-8%2B-orange)
![License](https://img.shields.io/badge/license-MIT-green)

</div>

---

## How it works

Players kill mobs and get **mobcoins** (with decimals: 2.5, 0.75...). Every mob can give a different amount with a
different chance. Players spend them in **shops** you design, also **rotating shops** whose items change every few
hours, with limited stock.

```
/mobcoins            your balance
/mobcoins menu       balance, shop, drops and top in one menu
/mobcoins shop       the shop
/mobcoins drops      what every mob gives
/mobcoins top weekly the best hunters of the week
/mobcoins stats      your kills, best combo and boosters
```

## Features

- **One jar from 1.8.8 to 26.x** (Spigot, Paper, Purpur...), Java 8+, no libraries inside
- **Mob drops** (`drops.yml`): by mob name or by group (hostile, passive, all), fixed or random amounts,
  chance, actions (messages, titles, sounds, broadcasts...), requirements, disabled worlds, spawner mobs off,
  RoseStacker / WildStacker support
- **Multipliers**: per player (`/mobcoins multiplier`), for the whole server, and by permission
  (`flaremobcoins.multiplier.vip: 1.5`)
- **Timed boosters**: `/mobcoins booster give <player> 2 1h`, or as an **item** to use later with a right click
  (sell them in your store); boss bar with the time left, they survive restarts
- **Events**: `/mobcoins event start 2 1h` = double mobcoins for everybody, with a boss bar and a countdown
  (with MySQL on every server of the network)
- **Golden mobs**: a few mobs spawn golden (name above them, shining outline) and give more mobcoins
  (on for new servers, off for servers that update)
- **Combo** (optional): kills in a row raise the multiplier for a few seconds; mob farms can't abuse it
- **Anti-farm** (optional): most mobcoins per hour from mobs, fewer mobcoins after many kills in the same chunk
- **MythicMobs** 4.x and 5.x: mobcoins for every custom mob by its name, a MYTHIC entry for all of them,
  extra mobcoins by level
- **Stats**: kills of every mob, mobcoins from mobs, golden mobs, best combo (`/mobcoins stats`)
- **Weekly and monthly top** with rewards when the week / month ends (given once also on a network)
- **Notes** (optional): `/mobcoins withdraw <amount>` turns mobcoins into an item to trade; every note can be
  redeemed once (copied notes give nothing)
- **Sell loot** (optional): `/mobcoins sell` sells rotten flesh, bones, gunpowder... for mobcoins
- **Coins on the ground** (optional): mobs drop a coin, only the killer can pick it up, close coins become one
- **In-game editor** (`/mobcoins editor`): move items with drag and drop, sell the item in your hand, set price,
  limit, stock, confirm menu, permission, rotation, commands and items given without opening a file
- **Settings menu** (`/mobcoins settings`): every option on / off or changed from a menu, written in the files
  with their comments; **player access** turns single commands off for normal players
- **Shops** like DeluxeMenus: any size, textured heads, products with a price, limits per player, stock for the
  server, confirm menu, items that change with the balance, left / right click, own open commands
- **Rotating shops**: normal and premium items that change by themselves, stock for everybody or per player,
  time left in the menu and in placeholders
- **Menus of the plugin** in `menus/` (main menu, top, mob drops, confirm), editable like the shops
- **Chest builder** (`/mobcoins layout add|import|chest|remove`): build a menu in a real chest, look at it and
  type one command; actions are written in the lore of the items
- **Pay** other players, **top** list (all time, week, month), cheap admin commands (`give/remove/set <player|*> <amount> [-s]`)
- **Storage**: YAML, SQLite or MySQL / MariaDB (several servers can share one database)
- **Texts**: MiniMessage, `&` colors, hex, gradients, rainbow, click and hover, PlaceholderAPI
- **Update from 3.x by itself**: balances, multipliers, drops and shops are kept
- **`/mobcoins about`**: version (green = latest, red = outdated), server, hooks and a clickable permission list;
  admins get a clickable message when a new version is out

## Commands

| Command | Permission | |
|---|---|---|
| `/mobcoins [balance] [player]` | `flaremobcoins.command.balance` (`.others`) | see a balance |
| `/mobcoins pay <player> <amount>` | `flaremobcoins.command.pay` | send mobcoins |
| `/mobcoins menu` | `flaremobcoins.command.menu` | your mobcoins menu (also `gui`) |
| `/mobcoins shop [shop] [player]` | `flaremobcoins.command.shop` (`.others`) | open a shop (`shop list`) |
| `/mobcoins drops [player]` | `flaremobcoins.command.drops` (`.other`) | menu with every drop |
| `/mobcoins top [weekly\|monthly] [chat]` | `flaremobcoins.command.top` | richest players, best of the week / month |
| `/mobcoins stats [player]` | `flaremobcoins.command.stats` (`.others`) | kills, mobcoins from mobs, best combo |
| `/mobcoins sell [hand\|all\|prices]` | `flaremobcoins.command.sell` | sell mob loot (when `sell.enabled`) |
| `/mobcoins withdraw <amount>` | `flaremobcoins.command.withdraw` | mobcoins into a note (when `withdraw.enabled`) |
| `/mobcoins booster [give\|item\|remove\|check]` | `flaremobcoins.command.booster` | timed boosters (players: their own) |
| `/mobcoins event [start\|stop]` | `flaremobcoins.command.event` | more mobcoins for everybody for some time |
| `/mobcoins give\|remove <player\|*> <amount> [-s]` | `flaremobcoins.command.give` / `.remove` | change a balance |
| `/mobcoins set <player> <amount> [-s]` | `flaremobcoins.command.set` | set a balance |
| `/mobcoins multiplier set\|reset\|global\|check` | `flaremobcoins.command.multiplier` | drop multipliers |
| `/mobcoins settings` | `flaremobcoins.command.settings` | every option in a menu |
| `/mobcoins editor [menu]` | `flaremobcoins.command.editor` | edit menus and shops in game (also `layout`) |
| `/mobcoins layout add\|import\|chest\|remove <menu>` | `flaremobcoins.command.editor` | chest builder |
| `/mobcoins reload` | `flaremobcoins.command.reload` | reload the files |
| `/mobcoins migrate <yaml\|sqlite\|mysql>` | `flaremobcoins.command.migrate` | copy every balance |
| `/mobcoins about [permissions]` | `flaremobcoins.command.version` | plugin info and updates (also `version`, `ver`) |

`flaremobcoins.player` (default: everyone) gives the player commands, `flaremobcoins.admin` (default: op) all of them.
`flaremobcoins.booster.use` uses booster items, `flaremobcoins.redeem` redeems notes (both everyone).
Aliases: `/fmob`, `/fmobcoin`, `/fmobcoins`, `/mobcoin` (the 3.x names `/mcoins`, `/flaremobcoins`, `/tmmobcoins`
keep working). The main shop also opens with `/mobcoinshop`.

## In-game editor

`/mobcoins editor` lists the menus of the plugin and every shop: left click edits, right click opens it like a
player, shift + right click turns it on / off, "New shop" creates one. In the editor:

- move, take out or put items with drag and drop (items of your inventory become decorations);
- **shift + right click** an empty slot: palette (glass, buttons to other menus, back / close, balance head,
  sell the item in your hand, rotating slots);
- **shift + right click** an item: admins only on / off;
- **shift + left click** an item: product settings (price, confirm, limit, stock, permission, rotation, name,
  commands, items given, delete);
- **shift + left click** a green / pink pane: the rotating items of that slot and how often they change.

Closing the editor saves the file.

## Chest builder

Build the menu in a chest (single = 3 rows, double = 6 rows, barrel, shulker box), look at it (at most 5 blocks
away) and type one command:

| Command | |
|---|---|
| `/mobcoins layout add <name>` | new shop `shops/<name>.yml` from the chest, opened with `/<name>` |
| `/mobcoins layout import <menu>` | the chest replaces the decorations of a menu or shop; its buttons stay where they are |
| `/mobcoins layout chest <menu>` | the other way round: the menu goes into the empty chest, change it and import it again |
| `/mobcoins layout remove <name>` | deletes a shop (asks to confirm, the file goes in `backups/`) |

Every item is kept as it is (name, lore, enchantments, heads, NBT). Lore lines that start with `[` are actions,
taken out of the lore: `[close]`, `[open] <menu>`, `[back]`, `[refresh]`, `[player] <cmd>`, `[console] <cmd>`,
`[chat] <text>`, `[message] <text>`, `[broadcast] <text>`, `[actionbar] <text>`, `[title] title;subtitle`,
`[sound] <sound>`, `[delay] <ticks>`, `[server] <name>`, `[buy(mobcoins)] <price>`... Options in the lore:

| Lore line | |
|---|---|
| `[left]` / `[right]` / `[shift-left]` / `[shift-right]` before an action | only that click |
| `[permission] <node>` | only players with it see the item |
| `[admin-only]` | only admins see it |
| `[update]` | redrawn every second (live placeholders) |
| `[button] <id>` | an item of the menu goes here (`shop`, `top`, `entries`... or `rotating-normal` / `rotating-premium`) |

A lore line that must really start with `[` is written `\[`. A head named `{player}` becomes the head of the player.
Before `import` and `remove` the old file is copied to `backups/`.

## Boosters, events, golden mobs, combo

Time: `30m`, `1h`, `1h30m`, `2d`. Boosters count real time (also while the player is offline). Two boosters with the
same multiplier add their time; with different ones the highest counts. A booster item can't be used while a stronger
one is active. Everything multiplies: permission × player × server × booster × event × golden mob × combo.

Golden mobs, combo, anti-farm and the MythicMobs options are in `drops.yml`; boosters, events, notes, sell and the
weekly / monthly rewards in `config.yml`. Every switch is also in `/mobcoins settings`.

## MythicMobs

```yaml
entity:
  skeleton_king:
    mythic: "SkeletonKing"      # name of the mob in MythicMobs (or a list)
    chance: 100
    amount: "50-100"
  every_mythic_mob:
    entity: MYTHIC               # every MythicMobs mob without its own entry
    amount: "5"
mythicmobs:
  normal-entries: true           # MythicMobs mobs without an entry use ZOMBIE, HOSTILE ...
  level-bonus: 10                # +10% for every level above 1
```

## Placeholders

`%flaremobcoins_mobcoins%` · `_mobcoins_formatted` (1.5k) · `_mobcoins_commas` · `_multiplier` · `_global_multiplier`
· `_position` · `_top_<n>_name` · `_top_<n>_mobcoins` · `_normal_shop_<shop>_time` · `_premium_shop_<shop>_time`

Weekly / monthly: `_top_weekly_<n>_name` · `_top_weekly_<n>_mobcoins` · `_top_monthly_<n>_name` · `_top_monthly_<n>_mobcoins`
· `_position_weekly` · `_position_monthly` · `_earned_weekly` · `_earned_monthly`

Stats and boosts: `_kills` · `_kills_<mob>` · `_earned` · `_golden_kills` · `_best_combo` · `_combo` · `_combo_multiplier`
· `_booster` · `_booster_time` · `_event_multiplier` · `_event_time` · `_event_active` · `_hour_left`

The 3.x placeholders (`get_mobcoins`, `get_commas_mobcoins`, `get_formatted_mobcoins`, `get_multiplier`,
`get_server_multiplier`) keep working.

## API

```java
double coins = FlareMobcoinsAPI.getMobcoins(player);
FlareMobcoinsAPI.giveMobcoins(player, 10.5);
boolean paid = FlareMobcoinsAPI.takeMobcoins(player, 25);   // false if he has less
```

Events: `MobcoinsChangeEvent` (every balance change, cancellable) and `MobCoinReceiveEvent`
(a mob drop, cancellable, the amount can be changed).

## Anonymous statistics

This plugin sends anonymous usage statistics to TMDevelopment every 30 minutes:
plugin version, Minecraft version, server software, Java version, CPU core count,
online player count, online/offline mode and a random server id generated on your server.
The country of the server is derived from its IP address (only the country code is kept).
FlareMobcoins also sends a few anonymous settings of its own: storage type (yaml, sqlite or mysql),
number of shops, number of mob drops (0 if they are off), whether coins on the ground, golden mobs, combo,
anti-farm, sell and withdraw are on, and whether MythicMobs is installed.
No player names or UUIDs, no IP addresses (they are never stored), no chat, world or config data.
Data of a server is deleted 30 days after its last report; only anonymous totals are kept.
We use it to know which versions to support.
You can turn it off for this plugin with `stats: false` in `config.yml`, or for all TMDevelopment plugins
in `plugins/TMDStats/config.yml` (`enabled: false`).
The update check only reads the latest release from GitHub: it sends nothing.

## Building

```
mvn package
```

The jar is in `target/FlareMobcoins-<version>.jar`.
