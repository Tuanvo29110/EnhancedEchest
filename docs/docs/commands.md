# Commands

EnhancedEChest has two command roots: one for players and one for admins. Click any command or permission to copy it to your clipboard.

<div style="display:flex;gap:8px;flex-wrap:wrap;margin:12px 0 24px;">
  <code style="padding:4px 12px;background:var(--vp-c-brand-soft);color:var(--vp-c-brand-1);border-radius:6px;font-weight:700;">/enderchest</code>
  <code style="padding:4px 12px;background:var(--vp-c-default-soft);color:var(--vp-c-text-2);border-radius:6px;font-weight:700;">/ec</code>
  <code style="padding:4px 12px;background:var(--vp-c-brand-soft);color:var(--vp-c-brand-1);border-radius:6px;font-weight:700;">/eclist</code>
  <code style="padding:4px 12px;background:var(--vp-c-brand-soft);color:var(--vp-c-brand-1);border-radius:6px;font-weight:700;">/enhancedechest</code>
  <code style="padding:4px 12px;background:var(--vp-c-default-soft);color:var(--vp-c-text-2);border-radius:6px;font-weight:700;">/ee</code>
</div>

::: tip
`/ec` is an alias for `/enderchest`, and `/ee` is an alias for `/enhancedechest`. `/eclist` opens the management menu directly. Opening the GUI by **command** requires `enhancedechest.command.open`; the `/enhancedechest` admin root requires `enhancedechest.admin`. Without the relevant permission the command is hidden. Right-clicking an ender chest block opens the GUI for everyone and needs **no permission**. See the [Permissions](/docs/permissions) page for the full list of nodes.
:::

## Player Commands

<div class="command-section">

<CommandRow commands="/enderchest" aliases="/ec" permission="enhancedechest.command.open">
Open your <strong>main</strong> ender chest. This is the same chest opened by right-clicking an ender chest block. If you don't have a chest yet, one is created automatically using the configured default size.
</CommandRow>

<CommandRow commands="/eclist" permission="enhancedechest.command.open">
Open the chest management menu — a dialog listing every ender chest you own. From here you can:
<ul>
<li><strong>Open</strong> any chest</li>
<li><strong>Rename</strong> a chest to give it a custom title</li>
<li><strong>Set as main</strong> to choose which chest <code>/enderchest</code> and the ender chest block open</li>
</ul>
</CommandRow>

<CommandRow :commands="['/enderchest #&lt;index&gt;', '/enderchest &lt;name&gt;']" permission="enhancedechest.command.open">
Open a specific chest directly, without going through the menu:
<ul>
<li><code>/enderchest #&lt;index&gt;</code>: Open the chest with that number, e.g. <code>/enderchest #2</code> (a bare number like <code>/enderchest 2</code> also works).</li>
<li><code>/enderchest &lt;name&gt;</code>: Open the chest whose custom name matches (case-insensitive). Names with spaces work too, e.g. <code>/enderchest My Tools</code>.</li>
</ul>
Index and name completions for your own chests are suggested as you type. If nothing matches, you're told the chest doesn't exist.
</CommandRow>

</div>

## Admin Commands

<div class="command-section">

<CommandRow commands="/ee reload" permission="enhancedechest.admin.reload">
Reload the plugin configuration and language files without restarting the server.
</CommandRow>

<CommandRow commands="/ee add &lt;player&gt; &lt;size&gt;" permission="enhancedechest.admin">
Create a new ender chest for a player. The chest is allocated the next free index automatically.
<ul>
<li><code>&lt;player&gt;</code>: Target player (online players are tab-completed)</li>
<li><code>&lt;size&gt;</code>: Slot count — a multiple of 9 between <code>9</code> and <code>54</code></li>
</ul>
</CommandRow>

<CommandRow commands="/ee resize &lt;player&gt; &lt;index&gt; &lt;size&gt;" permission="enhancedechest.admin">
Change the slot count of one of a player's existing chests.
<ul>
<li><code>&lt;index&gt;</code>: The chest number to resize (1 or higher)</li>
<li><code>&lt;size&gt;</code>: New slot count — a multiple of 9 between <code>9</code> and <code>54</code></li>
</ul>
</CommandRow>

<CommandRow commands="/ee delete &lt;player&gt; &lt;index&gt;" permission="enhancedechest.admin">
Delete one of a player's ender chests. If the deleted chest was the player's main chest, another chest is automatically promoted to take its place.
</CommandRow>

<CommandRow :commands="['/ee migrate run &lt;player&gt;', '/ee migrate run all']" permission="enhancedechest.admin.migrate.run">
Manually import vanilla ender chest contents into EnhancedEChest.
<ul>
<li><code>&lt;player&gt;</code>: Migrate a single online player</li>
<li><code>all</code>: Migrate every player currently online</li>
</ul>
Each player is migrated only once; players already migrated are skipped. See the <a href="/docs/migration">Migration</a> page for details.
</CommandRow>

</div>

<style scoped>
.command-section {
  border: 1px solid var(--vp-c-border);
  border-radius: 10px;
  overflow: hidden;
  margin-top: 24px;
  background-color: var(--vp-c-bg-soft);
}
</style>
