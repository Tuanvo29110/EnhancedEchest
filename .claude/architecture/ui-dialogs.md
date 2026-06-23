# Multi-chest UI & GUI guards

## Dialog API (`gui/dialog/ChestDialogs`)

Isolates Paper's experimental Dialog API so a breaking change is contained to one file. Three dialogs:

- **list** — one button per owned chest → opens the detail dialog. Marks the main with a gold `★`
  (`gui.yml dialog.main-tag`). Carries the edit-mode checkbox (see below).
- **detail** — Open / Rename / Set-as-main / Back (temp chests show only Open / Back, plus an
  "expires in" snapshot on the Open button).
- **rename** — text input + Save / Cancel (separate dialog, no inline input on detail).
- The icon picker is a single scrollable multi-action grid with a search input.
- **admin view list** (`adminViewListDialog`) — one Open button per chest for `/ee view <player> [list]`,
  opening the *target's* chest via `adminOpen`. No edit-mode/rename/set-main (those are owner-only); a
  plain Close exit button. Title shows the owner's name (`gui.yml dialog.admin-list-title`).

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
