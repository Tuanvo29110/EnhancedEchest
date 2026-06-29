# Multi-chest UI & GUI guards

## Dialog API (`gui/dialog/ChestDialogs`)

Isolates Paper's experimental Dialog API so a breaking change is contained to one file. Dialogs:

- **list** — one button per owned chest → opens the detail dialog. Marks the main with a gold `★`
  (`gui.yml dialog.main-tag`). Carries the edit-mode checkbox (see below).
- **detail** (`detailDialog(chest, DetailContext)`) — Open / Set-as-main / Rename / Choose icon / Sort /
  (admin) Clear / Back. A **single** dialog shared by the owner and an admin (`/ee view`); the
  `DetailContext` record decides the button set and *which owner* every mutation targets. Temp chests show
  only Open / (Clear) / Back, plus an "expires in" snapshot.
- **rename** (`renameDialog(chest, ctx)`) — text input + Save / Cancel (separate dialog, no inline input).
- The icon picker (`iconPickerDialog(chest, ctx, filter)`) is a single scrollable multi-action grid with a
  search input.
- **admin view list** (`adminViewListDialog`) — one button per chest for `/ee view <player> [list]`,
  opening the per-chest **detail** dialog for the *target* via `openAdminDetail`. Plain Close exit button;
  title shows the owner's name (`gui.yml dialog.admin-list-title`).
- **admin clear confirm** (`adminClearConfirmDialog`) — yes/no gate before `ChestSpillService.clearChest`.

### `DetailContext` (owner vs. admin, feature gating)

`ChestDialogs.DetailContext(owner, ownerName, self, canEdit, canSetMain, canClear, sourceBlock)` collapses
the old separate owner/admin detail dialogs into one path:

- **`owner`** — every storage mutation (rename, icon, sort, set-main) targets this UUID, *not* the clicker.
  That is how an admin edits another player's chest. `self`/`openDetailDialog(player,int)` builds a
  self-context (`owner = player`); `openAdminDetail` builds an admin-context (`owner = target`).
- **`self`** — picks Open routing (`openChest` vs. `adminOpen`) and Back routing (own list vs.
  `openAdminViewList`).
- **`canEdit`** — gates the appearance edits. `true` for the owner; for an admin it is
  `hasPermission(enhancedechest.admin.edit)`. Each edit is **also** gated by a global config toggle:
  Rename → `enderchest.features.rename`, Choose icon → `…features.icon`, Sort → `…features.sort` (read live
  from `PluginConfig`, which `ChestDialogs` holds a reference to — `/ee reload` mutates it in place; the
  three flags are `volatile`).
- **`canSetMain`** — owner-only (the open permission); always `false` for admins.
- **`canClear`** — admin Clear button, `hasPermission(enhancedechest.admin.clear)`; routes through the
  confirm dialog.

**Sort** is a server action, not a `show_dialog`: the button calls `ChestOpener.sortChest(viewer, ctx,
index)`, which enforces a per-clicker cooldown (`enderchest.features.sort-cooldown`, rejected with
`chest.sort-cooldown`) then calls `ChestSpillService.sortChest(owner, index)` — force-close all viewers +
`runExclusive` load→merge-similar→reorder-by-material-key→save (dupe-safe, mirrors `clearChest`). On
success it sends `chest.sorted` and re-pushes the detail dialog.

Navigation avoids cursor recentering: forward transitions use a client-side
`DialogAction.staticAction(ClickEvent.showDialog(child))` (child dialogs built first so parents can
reference them), while Back / Cancel / post-mutation paths re-query the DB and re-push via
`player.showDialog`. Dialog label text lives in `gui.yml` under `dialog:` (not `messages.yml`).

Item/block icons are rendered as Adventure sprite object components (no resource pack required).

## Edit-mode persistence flow

The edit-mode checkbox is a client-side `DialogInput.bool` that never notifies the server on toggle — its
value is only readable when a button carrying an action is clicked. So the preference is saved on **any**
action click that leaves the list (a chest button *or* Close), and only when it differs from the seeded
state, to avoid needless writes. Fresh list opens (`/eclist` and the routing dialog in `open`) seed the
checkbox from the saved value (`settingsCache`, see [storage-and-schema.md](storage-and-schema.md)). The
detail-dialog Back path forces edit-mode on — that is navigation, not preference. One gap is
unavoidable: closing with **Escape** fires no callback in the Dialog API, so a toggle followed by Escape
does not persist.

## Inventory GUI guards (`listener/EnderChestGuiListener`)

The custom chest inventory is identified by `inv.getHolder() instanceof EnderChestHolder`. The listener
enforces two rules on `InventoryClickEvent` / `InventoryDragEvent`:

1. **Read-only viewers** — a non-owner without `enhancedechest.admin.edit` (i.e. an admin who opened via
   `/ee view` with only `admin.view`). Any action that would change the shared top inventory is cancelled
   (`chest.view-only` on the action bar); they still see live updates. See
   [commands-and-permissions.md](commands-and-permissions.md).
2. **Temp chests are take-only** for everyone — deposits into the top inventory are cancelled
   (`chest.temp-take-only`, with a throttled deny sound), take-outs left untouched.

On close (and as a quit backstop in `PlayerQuitListener`), the listener calls `service.detach(...)`,
which removes the viewer from the shared session and, on the last viewer, persists — see
[concurrency-and-dupe-safety.md](concurrency-and-dupe-safety.md). The lid open/close animation
(`EnderChestAnimator`, pure `Lidded` API) is driven from the open/detach paths using the per-viewer
source block, dispatched to the block's region thread.
