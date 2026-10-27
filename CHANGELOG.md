# Changelog — FlareMobcoins (TMDevelopment, formerly FlaresDevelopment)

Versions go up by one step for every update: 4.0.1, 4.0.2, 4.0.3...

## 4.0.0
Rebuilt from scratch on the Flare base (same as FlareTokens 4 and FlareReferrals).

- One jar for Spigot, Paper, Purpur and other forks from **1.8.8 to 26.x**, Java 8 or newer.
  Newer features (boss bars, hex colors, titles with times, custom model data) are used only where the server has them.
- New text engine without libraries: MiniMessage tags, `&` codes, hex `&#rrggbb`, gradients, rainbow,
  click and hover in every message. The 3.x `<GRADIENT:#a-#b>` syntax still works.
- New files: `config.yml` with short explanations, chat messages in `lang/en.yml`, shops in `shops/`,
  mob drops in `drops.yml`. New options and messages are added by themselves in future updates.
- **Automatic update from 3.x**: old files saved in `backup-3.x/`, settings moved to the new config,
  shops moved to `shops/`, the global multiplier kept. Balances and player multipliers are not touched
  (same YAML file, SQLite file and MySQL table). Your 3.x `drops.yml` works as it is.
- Balances keep 2 decimals and go up to 90 trillion (3.x stopped at 99,999,999.99 with MySQL / SQLite).
- Online balances are kept in memory and saved on a separate thread: the server never waits for the database.
  Changes are atomic, so several servers can share one MySQL database (balances refresh every 10 seconds).
  Duplicated rows left by 3.x are merged automatically.
- `drops.yml`: entries by mob name (`ZOMBIE:`), groups `HOSTILE`, `PASSIVE`, `DEFAULT` and the 3.x format
  (`entity`, `entities`, `drop_value`, `drop_chance`, `drop_action`). New: `requirements`, `icon`,
  `disabled-worlds`, `ignore-spawner-mobs`, permission multipliers (`flaremobcoins.multiplier.vip: 1.5`).
- Multipliers: every drop is multiplied by the player multiplier × the server multiplier × the best permission
  multiplier. `/mobcoins multiplier set|reset|global|check`.
- `/mobcoins drops`: menu with every drop, its mobs, chance and amount (with pages, `menus/drops.yml`).
- Coins on the ground (`coins-on-ground`): coins of the same player close by become one, only the killer can pick
  them up, hoppers and mobs can't take them, they disappear after `despawn-seconds`. Now also on 1.8 - 1.13.
- Shops: same format as 3.x plus `rows`, `priority`, `view_requirements`, `[LEFT]` / `[RIGHT]` actions,
  `open_command` per shop, live items (`update: true`) and a click cooldown against double purchases.
- Rotating shops: normal and premium items change by themselves, `SERVER` stock (for everybody) or `PLAYER`
  stock (each player can buy X times), `{stock}` `{max_stock}` `{normal_time_left}` `{premium_time_left}`.
  The rotation and the stock are saved in `data/state.yml`, so a restart keeps the same items.
- Actions: `[buy(mobcoins)]`, `[buy(money)]` (Vault), `[buy(tokens)]` and `[tokens_give]` (FlareTokens),
  `[mobcoins_give]` / `[mobcoins_take]` / `[mobcoins_set]` with random amounts, `[BROADCAST]`, `[REFRESH]`,
  `[DELAY]`, `[OPEN_MENU]` (did nothing in 3.x), `[BOSSBAR]` short form.
- New checks: `[has_mobcoins] 100`, `[has_money] 100`, `[has_tokens] 100`, `[has_permission] node`,
  `[expression] a >= b` (the 3.x `check(...)` forms still work).
- `/mobcoins top`, `/mobcoins migrate <yaml|sqlite|mysql>`, `/mobcoins give|remove <player|*>`, amounts like `2.5k`,
  `-s` for silent changes, `/mobcoins shop list`.
- Fixed from 3.x: `requirement` in drops.yml worked the opposite way; `random_decimal(1,2)` gave 1 more than the
  maximum; `enable_stackers: true` turned stacked mobs off; the coin example `[mobcoins_give ]` gave nothing;
  give / set / remove sent the "received" message to the admin instead of the player; `/mobcoins balance <player>`
  showed a missing text; `[buy(...)]` failed with decimal prices; the help page used FlareTokens permissions;
  open requirements of a shop were not checked; `MobCoinReceiveEvent` could not be cancelled.
- Permissions: `flaremobcoins.player` (balance, pay, menu, shop, drops, top — on by default) and `flaremobcoins.admin`.
  Every permission has a description, listed by `/mobcoins about permissions`.
- PlaceholderAPI: new `%flaremobcoins_mobcoins%`, `_mobcoins_formatted`, `_mobcoins_commas`, `_multiplier`,
  `_global_multiplier`, `top_<n>_name`, `top_<n>_mobcoins`, `position`. The 3.x placeholders keep working.
- API: `FlareMobcoinsAPI` and `MobcoinsChangeEvent`. The 3.x classes `MobcoinsPlayer` and `MobCoinReceiveEvent`
  still work.
- `/mobcoins about` (also `version` / `ver`, and from the console): plugin version in green (latest) or red
  (outdated, with the download link), server version, Java, storage, update notifier, plugin hooks and a clickable
  list of every permission. On startup a new version is written in the console, and admins get a clickable message
  when they join. It reads the public GitHub releases (betas are never offered to stable servers) and keeps the
  answer for 6 hours. Turn it off with `update-check: false` in `config.yml`.
  Permission `flaremobcoins.command.version` (op, included in `flaremobcoins.admin`).
- **Admin menus**: `/mobcoins settings` (every option on / off or changed from a menu, written in the
  files with their comments), **player access** (menu, shop, drops, top and pay can be turned off for normal players:
  hidden from the help and the tab list) and `/mobcoins editor` to edit every menu and shop in game with drag and drop.
  Texts of the admin menus are in `menus/admin.yml`, like the other Flare plugins.
- **Chest builder**: `/mobcoins layout add <name>` makes a new shop from the chest you look at (chest, double chest,
  barrel, shulker box), `layout import <menu>` puts the chest layout on an existing menu (its buttons stay),
  `layout chest <menu>` puts a menu into an empty chest to change it by hand, `layout remove <name>` deletes a shop.
  Items are kept complete (enchantments, heads, NBT). Lore lines in `[...]` are actions and options: `[close]`,
  `[open] <menu>`, `[message] <text>`, `[left]` / `[right]` before an action, `[permission] <node>`, `[admin-only]`,
  `[update]`, `[button] <id>`. The old file is copied to `backups/` first.
- Products with a price: `price`, `give`, `commands`, `confirm` (confirm menu), `limit` per player and `stock` for the
  server (daily / weekly / monthly reset, safe with several servers on MySQL), `permission`. The editor sells the
  item in your hand with one click and sets everything without opening a file. Rotating items can be products too.
- Menus of the plugin in `menus/`: the main menu (`/mobcoins menu` or `gui`: balance, shop, drops, top), top
  (`/mobcoins top`, `/mobcoins top chat` for the chat list), mob drops and the confirm menu, editable like the shops.
- Menus react only to left / right clicks, with or without shift: Q, Ctrl+Q, number keys, F and double click do
  nothing (unless a shop item asks for them with `[DROP]`, `[NUMBER_KEY]`...).
- Open menus redraw themselves when the balance changes (prices, "you need X more", the balance head).
- New options and texts of future updates are added to `config.yml`, `lang/en.yml` and `menus/admin.yml` with their
  comments, in their section: the rest of the file is not touched (also on 1.8 - 1.17).
- On 1.8 the menu titles with a gradient are shortened without losing letters.
- Items given by a product stack with the same items got in another way.
- Offline-mode servers without BungeeCord / Velocity don't look up the skins of player heads (no console warnings).
- New aliases `/fmob`, `/fmobcoin`, `/fmobcoins` (the 3.x ones keep working).
- Every balance change is written in `logs/transactions.log` (mob drops excluded).
- Missing PlaceholderAPI is downloaded and started automatically.
- Anonymous usage statistics with TMDStats instead of bStats (plugin and Minecraft version, server software,
  Java version, player count, storage type, number of shops and drops, coins on ground on/off).
  No player names, no IPs. Turn them off with `stats: false` or in `plugins/TMDStats/config.yml`.
- The update check only reads the latest release from GitHub: it sends nothing.
- Removed: `check(javascript,...)` (Java no longer has a JavaScript engine), the HikariCP library inside the jar
  (the jar is less than half the size).
