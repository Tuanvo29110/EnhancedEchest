# Permissions

EnhancedEChest uses a small, layered permission system. Click any permission node to copy it to your clipboard.

## Default Values

| Value | Meaning |
|-------|---------|
| `op` | Only server operators have this permission by default |
| `true` | All players have this permission by default |
| `false` | No players have this permission by default; must be explicitly granted |

::: tip Letting everyone use ender chests
By default `ee.use` is operator-only. To let all players use their enhanced ender chest, grant `ee.use` to everyone through your permissions plugin (for example, set it to `true` for the default group).
:::

## Player Permissions

<BaseTable :columns="['Permission', 'Description', 'Default']" grid="2fr 3fr 0.6fr">

<PermRow permission="ee.use" defaultVal="op">
Allows opening the enhanced ender chest, either by right-clicking an ender chest block or with <code>/ec</code> and <code>/ec list</code>.
</PermRow>

</BaseTable>

## Admin Permissions

<BaseTable :columns="['Permission', 'Description', 'Default']" grid="2fr 3fr 0.6fr">

<PermRow permission="ee.admin" defaultVal="op">
Grants access to all admin commands, including <code>/ee add</code>, <code>/ee resize</code>, and <code>/ee delete</code>. Includes <code>ee.admin.reload</code> and <code>ee.admin.migrate.run</code> as children.
</PermRow>

<PermRow permission="ee.admin.reload" defaultVal="op">
Allows reloading the configuration with <code>/ee reload</code>.
</PermRow>

<PermRow permission="ee.admin.migrate.run" defaultVal="op">
Allows running migration for a player or all online players with <code>/ee migrate run</code>.
</PermRow>

</BaseTable>
