# Permissions

EnhancedEChest uses a small, layered permission system with two **separate base permissions** — one per command root. A command (and its subcommands) only appears and runs for players who hold its base node:

- `ec.use` — base permission for every `/ec` player command.
- `ee.admin` — base permission for every `/ee` admin command.

Click any permission node to copy it to your clipboard.

## Default Values

| Value | Meaning |
|-------|---------|
| `op` | Only server operators have this permission by default |
| `true` | All players have this permission by default |
| `false` | No players have this permission by default; must be explicitly granted |

::: tip Letting everyone use ender chests
By default `ec.use` is operator-only. To let all players use their enhanced ender chest, grant `ec.use` to everyone through your permissions plugin (for example, set it to `true` for the default group).
:::

## Player Permissions

<BaseTable :columns="['Permission', 'Description', 'Default']" grid="2fr 3fr 0.6fr">

<PermRow permission="ec.use" defaultVal="op">
Base permission for all <code>/ec</code> commands. Allows opening the enhanced ender chest by right-clicking an ender chest block or with <code>/ec</code>, <code>/ec list</code> (and <code>/eclist</code>), and <code>/ec #&lt;index&gt;</code> / <code>/ec &lt;name&gt;</code>. Without it, the <code>/ec</code> commands are hidden.
</PermRow>

</BaseTable>

## Admin Permissions

<BaseTable :columns="['Permission', 'Description', 'Default']" grid="2fr 3fr 0.6fr">

<PermRow permission="ee.admin" defaultVal="op">
Base permission for all <code>/ee</code> commands, including <code>/ee add</code>, <code>/ee resize</code>, and <code>/ee delete</code>. Includes <code>ee.admin.reload</code> and <code>ee.admin.migrate.run</code> as children. Without it, the <code>/ee</code> command is hidden.
</PermRow>

<PermRow permission="ee.admin.reload" defaultVal="op">
Allows reloading the configuration with <code>/ee reload</code>.
</PermRow>

<PermRow permission="ee.admin.migrate.run" defaultVal="op">
Allows running migration for a player or all online players with <code>/ee migrate run</code>.
</PermRow>

</BaseTable>
