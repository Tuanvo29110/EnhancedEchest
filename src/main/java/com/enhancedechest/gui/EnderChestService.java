package com.enhancedechest.gui;

import com.enhancedechest.gui.dialog.ChestDialogs;
import com.enhancedechest.lang.LanguageManager;
import com.enhancedechest.model.ChestKind;
import com.enhancedechest.model.ChestSummary;
import com.enhancedechest.model.EnderChestData;
import com.enhancedechest.model.PlayerSettings;
import com.enhancedechest.serialization.CodecException;
import com.enhancedechest.serialization.ContainerCodec;
import com.enhancedechest.storage.EnderChestStorage;
import com.tcoded.folialib.FoliaLib;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the open and save lifecycle of the custom ender chest GUIs, now multi-chest and
 * <b>multi-viewer</b>: a player and an admin (or two admins) can have the same chest open at once.
 *
 * <p>/enderchest and right-click open a single chest directly (auto-creating chest #1 if the player
 * owns none). With 2+ chests they open the chosen main directly if one is set (and the player may use
 * it), otherwise the management dialog. /eclist always opens the management dialog. A main is never
 * auto-assigned — it is set only via the dialog's "Set as main" action.
 *
 * <h2>Shared live inventory (concurrent-edit) model</h2>
 * Every open chest is backed by a single shared {@link Inventory} held in {@link #sessions}, keyed by
 * (owner, index). Owner and admin {@code openInventory()} the <i>same</i> object, so Bukkit serialises
 * all item moves on one {@code ItemStack[]} — making item-level duping between concurrent viewers
 * structurally impossible on a single-threaded platform.
 *
 * <p><b>Folia caveat:</b> two viewers may live on different region threads, where a shared inventory is
 * unsafe. On Folia we therefore allow only <b>one</b> live viewer per chest (a second opener is denied);
 * on Paper concurrent editing is fully supported.
 *
 * <p>All session bookkeeping (the {@link #sessions} map, viewer sets, attach/detach/persist decisions)
 * runs on a single thread via {@link #onGlobal}: the main thread on Paper, the global region thread on
 * Folia. This removes registry-level races on both. The actual DB read/write stays async; encoding is
 * synchronous and only ever happens once all viewers have closed (no concurrent edit during encode).
 *
 * <p>Dupe-safety contract (preserved, now per shared session):
 * <ul>
 *   <li>the <i>first</i> open of a chest waits for any in-flight async save of that same chest, then
 *       loads fresh from the DB; subsequent opens attach to the live session and never re-read the DB
 *       while it is open (the live inventory is authoritative).</li>
 *   <li>the chest is persisted when its <i>last</i> viewer closes (or a force-close fires), encoding the
 *       shared contents synchronously on the global thread then flushing to the DB on a daemon thread,
 *       keyed by (owner, index).</li>
 *   <li>flushPendingSaves() in onDisable() blocks until all writes finish before the pool closes.</li>
 * </ul>
 */
public final class EnderChestService {

    /** Permission to open the ender chest by command; also gates the dialog's "set as main" action. */
    private static final String OPEN_GUI_PERMISSION = "enhancedechest.command.open";

    /** Identifies an in-flight save by owner + chest index, so unrelated chests never block each other. */
    private record SaveKey(UUID owner, int index) {}

    /** A queued open waiting for its session's first DB load to finish. */
    private record Pending(Player player, @Nullable Location sourceBlock) {}

    /**
     * A live shared chest inventory and its current viewers. All fields are read and written only on the
     * {@link #onGlobal} thread, so no per-field synchronization is needed.
     */
    private static final class Session {
        final UUID owner;
        final int index;
        ChestKind kind;
        @Nullable Inventory inv;                 // null until the first DB load completes
        boolean ready;                           // inv is populated and viewers may attach
        boolean closing;                         // a force-close is persisting; new attaches are rejected
        final Set<UUID> viewers = new HashSet<>();
        final Map<UUID, Location> viewerBlocks = new HashMap<>();  // per-viewer source block for lid animation
        final List<Pending> waiting = new ArrayList<>();           // opens queued until ready

        Session(UUID owner, int index) {
            this.owner = owner;
            this.index = index;
        }
    }

    private final LanguageManager lang;
    private final ContainerCodec  codec;
    private final EnderChestStorage storage;
    private final Logger logger;
    private final FoliaLib foliaLib;
    private final ChestDialogs dialogs;

    // Runtime-tunable via /ee reload (see applyConfig). volatile so the value written on the main
    // thread during a reload is visible to the async open/close threads that read it.

    /** Size of the chest auto-created the first time a player ever opens their ender chest. */
    private volatile int defaultSize;

    /** Lifetime, in milliseconds, of a temp chest created when items spill on shrink/delete/expire. */
    private volatile long tempExpiryMillis;

    private final ConcurrentHashMap<SaveKey, CompletableFuture<Void>> pendingSaves =
            new ConcurrentHashMap<>();

    /** Live shared sessions, keyed by (owner, index). Mutated only on the {@link #onGlobal} thread. */
    private final ConcurrentHashMap<SaveKey, Session> sessions = new ConcurrentHashMap<>();

    // Write-through read cache of per-player settings, keyed by UUID. Populated on join
    // (preloadSettings), read by the dialog-open paths, updated in place on change, and evicted on
    // quit (evictSettings) — so it is bounded by the online-player count. Writes go straight to the DB
    // (write-through), so the cache holds no dirty state and needs no shutdown flush. See the leak-free
    // invariant documented on preloadSettings.
    private final ConcurrentHashMap<UUID, PlayerSettings> settingsCache = new ConcurrentHashMap<>();

    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "EnhancedEchest-db");
        t.setDaemon(true);
        return t;
    });

    public EnderChestService(LanguageManager lang, ContainerCodec codec,
                             EnderChestStorage storage, Logger logger, FoliaLib foliaLib,
                             int defaultSize, long tempExpiryMillis) {
        this.lang             = lang;
        this.codec            = codec;
        this.storage          = storage;
        this.logger           = logger;
        this.foliaLib         = foliaLib;
        this.defaultSize      = defaultSize;
        this.tempExpiryMillis = tempExpiryMillis;
        this.dialogs          = new ChestDialogs(this, lang);
    }

    /**
     * Re-applies the runtime-tunable config values after a {@code /ee reload}.
     *
     * <p>Both fields only affect work started <i>after</i> this call: {@code defaultSize} is read when
     * bootstrapping a brand-new chest, {@code tempExpiryMillis} when stamping a freshly spilled temp
     * chest. In-flight opens/saves are untouched, so this is dupe-safe to call on the main thread while
     * async storage work is pending. No new objects, threads, or tasks are allocated.
     */
    public void applyConfig(int defaultSize, long tempExpiryMillis) {
        this.defaultSize      = defaultSize;
        this.tempExpiryMillis = tempExpiryMillis;
    }

    /**
     * Runs {@code task} on the single bookkeeping thread (main on Paper, global region on Folia),
     * inline when already on it. All {@link #sessions} mutations funnel through here so the registry is
     * race-free across both platforms.
     */
    private void onGlobal(Runnable task) {
        if (foliaLib.getScheduler().isGlobalTickThread()) {
            task.run();
        } else {
            foliaLib.getScheduler().runNextTick(t -> task.run());
        }
    }

    // ---- opening ----

    /**
     * Default open entry point for {@code /enderchest} and right-click:
     * <ul>
     *   <li>0 or 1 normal chest and no temp chest — opens that chest directly (creating chest #1 if
     *       the player owns none);</li>
     *   <li>2+ normal chests, no temp chest, an explicit main is set <i>and</i> the player may use it
     *       — opens the main directly;</li>
     *   <li>otherwise — opens the management list dialog so the player picks (or sets a main).</li>
     * </ul>
     *
     * <p>A main is never auto-assigned at creation, so a multi-chest player who has not chosen one
     * always lands on the management dialog. Setting a main returns them to the open-directly path.
     * Players without the open-by-command permission can never have an effective main, so with 2+
     * chests they always get the dialog. Any TEMP (overflow) chest also forces the dialog, since
     * spilled items can only be retrieved from the list. {@code /eclist} still reaches the dialog
     * regardless.
     *
     * @param sourceBlock ender chest block location if opened via right-click; null for command/dialog
     */
    public void open(Player player, @Nullable Location sourceBlock) {
        UUID uuid = player.getUniqueId();
        boolean canSetMain = canSetMain(player);
        foliaLib.getScheduler().runAtEntity(player, outerTask -> {
            closeExistingGui(player);
            listChestsAsync(uuid)
                    .thenAccept(chests -> {
                        // Spilled items live in TEMP chests that can ONLY be retrieved from the list
                        // dialog, so any temp chest forces the dialog regardless of how many normal
                        // chests exist (otherwise a single-chest player could never reach the overflow).
                        boolean hasTemp = chests.stream().anyMatch(c -> c.kind() == ChestKind.TEMP);
                        long normalCount = chests.stream().filter(c -> c.kind() == ChestKind.NORMAL).count();
                        // 0 or 1 normal chest and nothing spilled: open it directly (bootstrapping
                        // chest #1 if the player owns none).
                        if (!hasTemp && normalCount <= 1) {
                            openPrimaryChest(player, uuid, sourceBlock);
                            return;
                        }
                        // 2+ chests: only an explicitly-flagged main, set by a player who may use it,
                        // bypasses the dialog — and never while a temp chest is awaiting recovery.
                        // Otherwise show the management list.
                        Integer mainIndex = (!hasTemp && canSetMain)
                                ? chests.stream().filter(ChestSummary::primary)
                                        .map(ChestSummary::index).findFirst().orElse(null)
                                : null;
                        if (mainIndex != null) {
                            openChest(player, mainIndex, sourceBlock);
                        } else {
                            // Seed the edit-mode checkbox from the player's saved preference.
                            loadSettingsAsync(uuid).thenAccept(settings ->
                                    foliaLib.getScheduler().runAtEntity(player, task -> {
                                        if (player.isOnline()) player.showDialog(
                                                dialogs.listDialog(chests, canSetMain, sourceBlock, settings.editMode()));
                                    }));
                        }
                    })
                    .exceptionally(e -> reportOpenFailure(player, e));
        });
    }

    /** Loads and opens the player's primary chest inventory directly (creating chest #1 if none exist). */
    private void openPrimaryChest(Player player, UUID uuid, @Nullable Location sourceBlock) {
        CompletableFuture
                .supplyAsync(() -> resolvePrimaryIndex(uuid), asyncExecutor)
                .thenAccept(index -> openShared(player, uuid, index, sourceBlock))
                .exceptionally(e -> reportOpenFailure(player, e));
    }

    /**
     * Opens a chest selected by a free-text query from {@code /ec <chest>}:
     * <ul>
     *   <li>{@code #N} (or a bare positive integer) opens the chest with that index;</li>
     *   <li>anything else is matched case-insensitively against players' custom chest names.</li>
     * </ul>
     * A miss reports {@code chest.unknown} rather than silently opening the primary chest.
     */
    public void openByQuery(Player player, String query) {
        String trimmed = query.trim();
        Integer index = parseIndexQuery(trimmed);
        if (index != null) {
            openChest(player, index, null);
            return;
        }
        UUID uuid = player.getUniqueId();
        listChestsAsync(uuid).thenAccept(chests ->
                foliaLib.getScheduler().runAtEntity(player, task -> {
                    if (!player.isOnline()) return;
                    chests.stream()
                            .filter(c -> c.customName() != null
                                    && c.customName().equalsIgnoreCase(trimmed))
                            .findFirst()
                            .ifPresentOrElse(
                                    c -> openChest(player, c.index(), null),
                                    () -> player.sendMessage(lang.get("chest.unknown", "query", trimmed)));
                })
        ).exceptionally(e -> reportOpenFailure(player, e));
    }

    /** Parses a {@code #N} or bare-integer index query; returns null for non-index input. */
    private static Integer parseIndexQuery(String query) {
        String digits = query.startsWith("#") ? query.substring(1) : query;
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            int value = Integer.parseInt(digits);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Opens a specific chest by index (from the management dialog), sharing the live session. */
    public void openChest(Player player, int index, @Nullable Location sourceBlock) {
        openShared(player, player.getUniqueId(), index, sourceBlock);
    }

    /**
     * Opens another player's chest for an admin, sharing the live session. The admin becomes a viewer of
     * the same inventory the owner sees (concurrent edit on Paper; exclusive on Folia). Read-only vs
     * editable is enforced per-click in the GUI listener via the admin's permissions, so this method
     * itself simply joins the session.
     */
    public void adminOpen(Player admin, UUID owner, int index) {
        openShared(admin, owner, index, null);
    }

    /**
     * The single funnel through which every chest open passes. Closes the player's current chest GUI on
     * their entity thread (flushing its session), then hands off to {@link #decideOpen} on the global
     * bookkeeping thread to attach to — or create — the live session for {@code (owner, index)}.
     */
    private void openShared(Player player, UUID owner, int index, @Nullable Location sourceBlock) {
        foliaLib.getScheduler().runAtEntity(player, t -> {
            if (!player.isOnline()) return;
            closeExistingGui(player);
            onGlobal(() -> decideOpen(player, owner, index, sourceBlock));
        });
    }

    /** Global-thread decision: attach to an existing live session, or create one and load it fresh. */
    private void decideOpen(Player player, UUID owner, int index, @Nullable Location sourceBlock) {
        SaveKey key = new SaveKey(owner, index);
        UUID viewer = player.getUniqueId();
        Session existing = sessions.get(key);
        if (existing != null && !existing.closing) {
            if (foliaLib.isFolia() && isOccupiedByOther(existing, viewer)) {
                notifyOnPlayer(player, "chest.in-use");
                return;
            }
            if (existing.ready) {
                addViewerAndOpen(player, existing, sourceBlock);
            } else {
                existing.waiting.add(new Pending(player, sourceBlock));
            }
            return;
        }

        // No live session: create one and load fresh after any in-flight save for this key.
        Session created = new Session(owner, index);
        created.waiting.add(new Pending(player, sourceBlock));
        sessions.put(key, created);
        waitPending(owner, index)
                .thenCompose(v -> CompletableFuture.supplyAsync(() -> storage.loadChest(owner, index), asyncExecutor))
                .whenComplete((data, err) -> onGlobal(() -> finishCreate(key, created, data, err)));
    }

    /** True if a viewer (or a queued opener) other than {@code self} already holds this session. */
    private static boolean isOccupiedByOther(Session s, UUID self) {
        for (UUID u : s.viewers) {
            if (!u.equals(self)) return true;
        }
        for (Pending p : s.waiting) {
            if (!p.player().getUniqueId().equals(self)) return true;
        }
        return false;
    }

    /** Global-thread completion of a first load: build the shared inventory and flush the waiting queue. */
    private void finishCreate(SaveKey key, Session created,
                              @Nullable EnderChestData data, @Nullable Throwable err) {
        List<Pending> waiters = new ArrayList<>(created.waiting);
        created.waiting.clear();

        // A force-close (admin resize/delete) may have superseded this session while the load was in flight.
        boolean stale = sessions.get(key) != created || created.closing;
        if (stale || err != null || data == null) {
            sessions.remove(key, created);
            for (Pending p : waiters) {
                if (err != null) reportOpenFailure(p.player(), err);
                else notifyOnPlayer(p.player(), "chest.not-found");
            }
            return;
        }

        Inventory inv = buildSharedInventory(data);
        if (inv == null) {
            sessions.remove(key, created);
            for (Pending p : waiters) notifyOnPlayer(p.player(), "chest.codec-failed");
            return;
        }
        created.kind  = data.kind();
        created.inv   = inv;
        created.ready = true;
        for (Pending p : waiters) addViewerAndOpen(p.player(), created, p.sourceBlock());
    }

    /**
     * Builds the shared {@link Inventory} for a chest, decoding its stored contents. The holder carries
     * no source block (block animation is tracked per-viewer in the session). Returns null if the stored
     * bytes fail to decode, so the caller can abort the open rather than risk corrupting the data.
     */
    private @Nullable Inventory buildSharedInventory(EnderChestData data) {
        int size = data.size();
        Component title = lang.getChestLabel(data.index(), data.customName(), data.kind());
        Inventory inv = Bukkit.createInventory(
                new EnderChestHolder(data.owner(), data.index(), size, data.kind(), null), size, title);

        if (data.containerData() != null && data.containerData().length > 0) {
            try {
                inv.setContents(codec.decode(data.containerData(), size));
            } catch (CodecException e) {
                logger.error("Codec failure for {} chest {} — aborting open to protect stored data",
                        data.owner(), data.index(), e);
                return null;
            }
        }
        return inv;
    }

    /**
     * Global-thread: registers the player as a viewer of the live session, then opens the shared
     * inventory for them on their entity thread (playing the lid animation if opened from a block).
     */
    private void addViewerAndOpen(Player player, Session s, @Nullable Location sourceBlock) {
        UUID uuid = player.getUniqueId();
        s.viewers.add(uuid);
        if (sourceBlock != null) s.viewerBlocks.put(uuid, sourceBlock);
        Inventory inv = s.inv;
        foliaLib.getScheduler().runAtEntity(player, task -> {
            if (!player.isOnline() || inv == null) {
                onGlobal(() -> removeViewer(s, uuid));
                return;
            }
            player.openInventory(inv);
            if (sourceBlock != null) {
                foliaLib.getScheduler().runAtLocation(sourceBlock, lt ->
                        EnderChestAnimator.open(player, sourceBlock));
            }
        });
    }

    /**
     * Global-thread: drops a viewer that never actually opened (offline by the time the open ran),
     * persisting and tearing down the session if it leaves no viewers behind. No animation (the chest
     * was never shown to this viewer).
     */
    private void removeViewer(Session s, UUID uuid) {
        s.viewers.remove(uuid);
        s.viewerBlocks.remove(uuid);
        if (!s.closing && s.viewers.isEmpty() && s.waiting.isEmpty()) {
            sessions.remove(new SaveKey(s.owner, s.index), s);
            persist(s);
        }
    }

    private int resolvePrimaryIndex(UUID uuid) {
        int index = storage.getPrimaryIndex(uuid);
        if (index == -1) {
            // First-ever access: bootstrap chest #1. It is NOT flagged primary — with a single chest,
            // getPrimaryIndex falls back to the lowest index, so /ec still opens it directly.
            index = storage.createChest(uuid, defaultSize);
        }
        return index;
    }

    private void closeExistingGui(Player player) {
        Inventory currentTop = player.getOpenInventory().getTopInventory();
        if (currentTop.getHolder() instanceof EnderChestHolder) {
            player.closeInventory();
        }
    }

    private Void reportOpenFailure(Player player, Throwable e) {
        logger.error("Failed to load enderchest for {} — aborting open", player.getName(),
                e.getCause() != null ? e.getCause() : e);
        foliaLib.getScheduler().runAtEntity(player, t -> {
            if (player.isOnline()) player.sendMessage(lang.get("chest.load-failed"));
        });
        return null;
    }

    /** Sends a localized message to the player on their entity thread (if still online). */
    private void notifyOnPlayer(Player player, String key) {
        foliaLib.getScheduler().runAtEntity(player, t -> {
            if (player.isOnline()) player.sendMessage(lang.get(key));
        });
    }

    // ---- management dialog ----

    /** Loads the player's chests and shows the /eclist management dialog, seeding edit mode from their saved preference. */
    public void openListDialog(Player player) {
        loadSettingsAsync(player.getUniqueId())
                .thenAccept(settings -> openListDialog(player, settings.editMode(), null))
                .exceptionally(e -> reportOpenFailure(player, e));
    }

    /**
     * Loads the player's chests and shows the management dialog with the edit-mode checkbox in the
     * given starting state. Fresh opens seed it from the player's saved preference (see the no-arg
     * overload and {@link #open}); returning from a detail dialog's Back seeds it on so the player
     * stays in edit mode. The checkbox itself toggles client-side without re-showing.
     *
     * @param editInitial starting state of the dialog's edit-mode checkbox
     * @param sourceBlock ender chest block this menu was opened from (threaded through so direct opens
     *                    still animate), or null when opened by command
     */
    public void openListDialog(Player player, boolean editInitial, @Nullable Location sourceBlock) {
        UUID uuid = player.getUniqueId();
        boolean canSetMain = canSetMain(player);
        listChestsAsync(uuid).thenAccept(chests ->
                foliaLib.getScheduler().runAtEntity(player, task -> {
                    if (!player.isOnline()) return;
                    if (chests.isEmpty()) {
                        player.sendMessage(lang.get("chest.none"));
                        return;
                    }
                    player.showDialog(dialogs.listDialog(chests, canSetMain, sourceBlock, editInitial));
                })
        ).exceptionally(e -> reportOpenFailure(player, e));
    }

    /**
     * Shows the admin "view another player's chests" list dialog. Each button opens the target's chest
     * for the admin via the shared session ({@link #adminOpen}) — no edit-mode/rename/set-main. The
     * chests are passed in already loaded (the command lists them to route 0/1/2+), so this only builds
     * and pushes the dialog on the admin's entity thread.
     */
    public void showAdminViewList(Player admin, String targetName, UUID target, List<ChestSummary> chests) {
        foliaLib.getScheduler().runAtEntity(admin, t -> {
            if (admin.isOnline()) admin.showDialog(dialogs.adminViewListDialog(targetName, target, chests));
        });
    }

    /** Shows the per-chest detail dialog (Open / Rename / Set-main / Back). */
    public void openDetailDialog(Player player, int index) {
        boolean canSetMain = canSetMain(player);
        showChestDialog(player, index, chest -> dialogs.detailDialog(chest, canSetMain, null));
    }

    /**
     * Whether the player may set a chest as their main: gated on the open-by-command permission,
     * since the "main" chest only matters when {@code /enderchest} can be used to open it.
     */
    private boolean canSetMain(Player player) {
        return player.hasPermission(OPEN_GUI_PERMISSION);
    }

    /** Shows the dedicated rename dialog for a chest. */
    public void openRenameDialog(Player player, int index) {
        showChestDialog(player, index, dialogs::renameDialog);
    }

    /** Loads the chest by index and shows a dialog built from it on the player's thread. */
    private void showChestDialog(Player player, int index,
                                 java.util.function.Function<ChestSummary, io.papermc.paper.dialog.Dialog> builder) {
        UUID uuid = player.getUniqueId();
        listChestsAsync(uuid).thenAccept(chests ->
                foliaLib.getScheduler().runAtEntity(player, task -> {
                    if (!player.isOnline()) return;
                    chests.stream().filter(c -> c.index() == index).findFirst().ifPresentOrElse(
                            c -> player.showDialog(builder.apply(c)),
                            () -> player.sendMessage(lang.get("chest.not-found")));
                })
        ).exceptionally(e -> reportOpenFailure(player, e));
    }

    // ---- closing / saving ----

    /**
     * Detaches a viewer when they close the shared GUI (called from the GUI close and quit listeners on
     * the player's entity thread). Removes them from the session on the global thread and, if they were
     * the <i>last</i> viewer, persists the shared contents. A no-op if the session was already torn down
     * by a force-close (which persists itself) — so the same close never double-saves.
     */
    public void detach(Player player, EnderChestHolder holder) {
        UUID uuid = player.getUniqueId();
        SaveKey key = new SaveKey(holder.getOwner(), holder.getIndex());
        onGlobal(() -> {
            Session s = sessions.get(key);
            if (s == null) return;                         // already force-closed and persisted

            boolean wasViewer = s.viewers.remove(uuid);
            Location block = s.viewerBlocks.remove(uuid);
            if (wasViewer && block != null) {
                foliaLib.getScheduler().runAtLocation(block, lt ->
                        EnderChestAnimator.close(player, block));
            }

            if (s.closing) return;                         // force-close path owns persistence
            if (s.viewers.isEmpty() && s.waiting.isEmpty()) {
                sessions.remove(key, s);
                persist(s);
            }
        });
    }

    /**
     * Persists a session's shared inventory to the database (must be called on the global thread, with
     * no viewer still editing). Encodes bytes synchronously, then writes on a daemon thread, keyed by
     * (owner, index) and registered in {@link #pendingSaves} so a concurrent open/op waits for it.
     * An emptied TEMP chest removes itself instead of persisting an empty row.
     */
    private void persist(Session s) {
        Inventory inv = s.inv;
        if (inv == null) return;                           // never became ready; nothing to save
        UUID owner = s.owner;
        int index = s.index;

        if (s.kind == ChestKind.TEMP && isInventoryEmpty(inv)) {
            runExclusive(owner, index, () -> { storage.deleteChest(owner, index); return null; })
                    .exceptionally(e -> {
                        logger.error("Failed to remove emptied temp chest {} for {}", index, owner, e);
                        return null;
                    });
            return;
        }

        byte[] encoded;
        try {
            encoded = codec.encode(inv.getContents());
        } catch (Exception e) {
            logger.error("Codec encode failure for {} chest {} — data NOT saved to prevent corruption",
                    owner, index, e);
            return;
        }

        SaveKey key = new SaveKey(owner, index);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                storage.saveChest(owner, index, encoded);
            } catch (Exception e) {
                logger.error("DB save failure for {} chest {}", owner, index, e);
            }
        }, asyncExecutor);

        pendingSaves.put(key, future);
        future.whenComplete((v, e) -> pendingSaves.remove(key, future));
    }

    private CompletableFuture<Void> waitPending(UUID uuid, int index) {
        return pendingSaves.getOrDefault(new SaveKey(uuid, index),
                CompletableFuture.completedFuture(null));
    }

    // ---- async storage operations (used by dialog callbacks and admin commands) ----

    public CompletableFuture<List<ChestSummary>> listChestsAsync(UUID owner) {
        return CompletableFuture.supplyAsync(() -> storage.listChests(owner), asyncExecutor);
    }

    /** Resolves the index {@code /ec} would open for a player (primary, or lowest; -1 if they own none). */
    public CompletableFuture<Integer> getPrimaryIndexAsync(UUID owner) {
        return CompletableFuture.supplyAsync(() -> storage.getPrimaryIndex(owner), asyncExecutor);
    }

    public CompletableFuture<Integer> createChestAsync(UUID owner, int size) {
        return createChestAsync(owner, size, null);
    }

    /** Creates a NORMAL chest, expirable if {@code expiresAt} is non-null (epoch millis). */
    public CompletableFuture<Integer> createChestAsync(UUID owner, int size, @Nullable Long expiresAt) {
        return CompletableFuture.supplyAsync(() -> storage.createChest(owner, size, expiresAt), asyncExecutor);
    }

    /** Convenience for an expirable granted chest: expires {@code durationMillis} from now. */
    public CompletableFuture<Integer> addExpirableChest(UUID owner, int size, long durationMillis) {
        return createChestAsync(owner, size, System.currentTimeMillis() + durationMillis);
    }

    public CompletableFuture<Void> renameAsync(UUID owner, int index, @Nullable String name) {
        return CompletableFuture.runAsync(() -> storage.renameChest(owner, index, name), asyncExecutor);
    }

    /** Sets or clears a chest's icon (material key, or null to reset to the default icon). */
    public CompletableFuture<Void> setIconAsync(UUID owner, int index, @Nullable String icon) {
        return CompletableFuture.runAsync(() -> storage.setIcon(owner, index, icon), asyncExecutor);
    }

    public CompletableFuture<Void> setPrimaryAsync(UUID owner, int index) {
        return CompletableFuture.runAsync(() -> storage.setPrimary(owner, index), asyncExecutor);
    }

    public CompletableFuture<Void> clearPrimaryAsync(UUID owner) {
        return CompletableFuture.runAsync(() -> storage.clearPrimary(owner), asyncExecutor);
    }

    /**
     * Loads a player's settings into the cache on join. This is the cache's <b>only</b> inserter,
     * which keeps the leak-free invariant simple: every entry added here is removed by
     * {@link #evictSettings} on quit. The post-load online re-check covers the join-then-immediate-quit
     * race — if the player already left while the load was in flight (so {@code evictSettings} ran
     * before this put), the entry is dropped right after it is added, so nothing is ever orphaned.
     */
    public void preloadSettings(UUID owner) {
        CompletableFuture.supplyAsync(() -> storage.loadSettings(owner), asyncExecutor)
                .thenAccept(settings -> {
                    settingsCache.put(owner, settings);
                    if (Bukkit.getPlayer(owner) == null) {
                        settingsCache.remove(owner);
                    }
                })
                .exceptionally(e -> {
                    logger.error("Failed to preload settings for {}", owner, e.getCause() != null ? e.getCause() : e);
                    return null;
                });
    }

    /** Evicts a player's cached settings on quit. Paired with {@link #preloadSettings} so the cache stays bounded by online players. */
    public void evictSettings(UUID owner) {
        settingsCache.remove(owner);
    }

    /**
     * Returns the player's settings, served from the cache when present (the common case for an online
     * player). A miss — preload still in flight, or the player was already online before the plugin
     * loaded — falls back to a one-off DB read that is deliberately <b>not</b> cached, so
     * {@link #preloadSettings} remains the sole inserter and the leak-free invariant holds.
     */
    public CompletableFuture<PlayerSettings> loadSettingsAsync(UUID owner) {
        PlayerSettings cached = settingsCache.get(owner);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> storage.loadSettings(owner), asyncExecutor);
    }

    /**
     * Persists the player's edit-mode preference with a single targeted upsert (no preceding read),
     * leaving every other setting untouched. Write-through: the cached copy is updated in place first
     * (if present) so the next dialog open reflects the change without a DB read, then the DB is
     * written. Uses {@code computeIfPresent} so it never inserts — preserving the leak-free invariant.
     */
    public CompletableFuture<Void> setEditModeAsync(UUID owner, boolean editMode) {
        settingsCache.computeIfPresent(owner, (k, s) -> s.withEditMode(editMode));
        return CompletableFuture.runAsync(() -> storage.setEditMode(owner, editMode), asyncExecutor);
    }

    /** Runs the given action on the player's entity thread (helper for command/dialog callbacks). */
    public void runForPlayer(Player player, Runnable action) {
        foliaLib.getScheduler().runAtEntity(player, task -> action.run());
    }

    // ---- item-moving operations (shrink spill / delete spill / expiry) ----

    /**
     * Resizes a chest, spilling any cut-off items into a temp chest if it is shrunk below its used
     * slots. Force-closes every viewer's GUI first (flushing the live session), then runs the
     * load-decode-split exclusively per (owner, index) so no concurrent open sees a half-applied state.
     * A grow, or a shrink that loses no items, is a plain resize.
     */
    public CompletableFuture<Void> resizeOrSpill(UUID owner, int index, int newSize) {
        return forceCloseAll(owner, index).thenCompose(v -> runExclusive(owner, index, () -> {
            EnderChestData data = storage.loadChest(owner, index);
            if (data == null) return null;

            ItemStack[] all = decodeAll(data);
            // Nothing occupies a slot at or beyond newSize → a plain resize loses no items.
            if (lastUsedSlot(all) < newSize) {
                storage.resizeChest(owner, index, newSize);
                return null;
            }

            ItemStack[] visible  = Arrays.copyOfRange(all, 0, newSize);
            ItemStack[] overflow = Arrays.copyOfRange(all, newSize, all.length);
            byte[] visibleBytes  = codec.encode(visible);
            byte[] overflowBytes = codec.encode(overflow);
            storage.spillShrink(owner, index, newSize, visibleBytes, overflowBytes,
                    requiredTempSize(overflow), System.currentTimeMillis() + tempExpiryMillis);
            return null;
        }));
    }

    /**
     * Removes a chest. With {@code force} the row is hard-deleted (items lost immediately); otherwise
     * any items are spilled into a temp chest first. Force-closes every viewer's GUI, then performs the
     * delete exclusively per (owner, index) so the swap is dupe-safe. Used by {@code /ee delete} and
     * by the expiry sweeper (NORMAL → spill, TEMP → force).
     */
    public CompletableFuture<Void> removeChest(UUID owner, int index, boolean force) {
        return forceCloseAll(owner, index).thenCompose(v -> runExclusive(owner, index, () -> {
            if (force) {
                storage.deleteChest(owner, index);
                return null;
            }
            EnderChestData data = storage.loadChest(owner, index);
            byte[] items = null;
            int tempSize = 0;
            if (data != null && data.containerData() != null && data.containerData().length > 0
                    && lastUsedSlot(decodeAll(data)) >= 0) {
                // Reuse the already-encoded bytes; the temp chest mirrors the original chest's size.
                items = data.containerData();
                tempSize = data.size();
            }
            storage.spillRemove(owner, index, items, tempSize,
                    System.currentTimeMillis() + tempExpiryMillis);
            return null;
        }));
    }

    /**
     * Bulk-removes the {@code count} newest (highest-index) NORMAL chests a player owns, spilling (or
     * force-discarding) each, and completes with the number actually removed. The player's <i>first</i>
     * chest — the lowest-indexed NORMAL chest — is always protected, so a player can never be left with
     * no chests; deleting fewer than {@code count} when that is all that is eligible is not an error.
     * Temp chests are ignored (they are transient and expire on their own).
     *
     * <p>Targets are snapshotted up front, then deleted sequentially: a spilling delete creates a fresh
     * temp chest at a higher index, but those are not in the target list so they are never re-touched.
     * Each per-index delete still serializes behind its own pending saves and force-closes open GUIs,
     * so the bulk op is dupe-safe.
     */
    public CompletableFuture<Integer> removeNewestChests(UUID owner, int count, boolean force) {
        return listChestsAsync(owner).thenCompose(chests -> {
            // Only NORMAL chests are eligible; sorted ascending so element 0 is the protected first chest.
            List<ChestSummary> normal = chests.stream()
                    .filter(c -> c.kind() == ChestKind.NORMAL)
                    .sorted(Comparator.comparingInt(ChestSummary::index))
                    .toList();
            if (normal.size() <= 1) {
                return CompletableFuture.completedFuture(0);
            }
            // Everything except the protected first chest, newest (highest index) first, capped at count.
            List<Integer> targets = normal.subList(1, normal.size()).stream()
                    .map(ChestSummary::index)
                    .sorted(Comparator.reverseOrder())
                    .limit(Math.max(0, count))
                    .toList();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int index : targets) {
                chain = chain.thenCompose(v -> removeChest(owner, index, force));
            }
            return chain.thenApply(v -> targets.size());
        });
    }

    /**
     * Serializes arbitrary DB work for one (owner, index) behind any in-flight save/op for that key,
     * registering it in {@code pendingSaves} so a concurrent {@code open} waits for it. The work runs
     * on the async executor; the returned future completes with its result (or its failure).
     */
    private <T> CompletableFuture<T> runExclusive(UUID owner, int index, Supplier<T> dbWork) {
        SaveKey key = new SaveKey(owner, index);
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> marker = result.handle((v, e) -> null);
        // Atomically chain after whatever is currently pending for this key.
        pendingSaves.compute(key, (k, prev) -> {
            CompletableFuture<Void> base = (prev != null) ? prev : CompletableFuture.completedFuture(null);
            base.whenComplete((v, e) ->
                    CompletableFuture.supplyAsync(dbWork, asyncExecutor).whenComplete((r, err) -> {
                        if (err != null) result.completeExceptionally(err);
                        else result.complete(r);
                    }));
            return marker;
        });
        marker.whenComplete((v, e) -> pendingSaves.remove(key, marker));
        return result;
    }

    /**
     * Force-closes the GUI of <b>every</b> viewer of {@code (owner, index)}, then persists the shared
     * contents and tears down the session — returning a future that completes once the save has been
     * registered in {@link #pendingSaves}. The caller can then chain an exclusive op that serialises
     * behind that save, keeping admin resize/delete dupe-safe even with multiple concurrent viewers.
     *
     * <p>The persist runs only after all viewer screens have actually closed (their close handlers see
     * {@code closing} and skip their own save), so the shared inventory is read with no viewer still
     * editing it — safe to encode on the global thread even on Folia.
     */
    private CompletableFuture<Void> forceCloseAll(UUID owner, int index) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        onGlobal(() -> {
            SaveKey key = new SaveKey(owner, index);
            Session s = sessions.get(key);
            if (s == null) {
                done.complete(null);
                return;
            }
            s.closing = true;
            List<CompletableFuture<?>> closes = new ArrayList<>();
            for (UUID uuid : new ArrayList<>(s.viewers)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                CompletableFuture<Void> c = new CompletableFuture<>();
                closes.add(c);
                foliaLib.getScheduler().runAtEntity(p, t -> {
                    try {
                        Inventory top = p.getOpenInventory().getTopInventory();
                        if (top.getHolder() instanceof EnderChestHolder h
                                && h.getOwner().equals(owner) && h.getIndex() == index) {
                            p.closeInventory();
                        }
                    } finally {
                        c.complete(null);
                    }
                });
            }
            CompletableFuture.allOf(closes.toArray(new CompletableFuture[0])).whenComplete((v, e) ->
                    onGlobal(() -> {
                        persist(s);                        // authoritative state; all viewers now closed
                        sessions.remove(key, s);
                        done.complete(null);
                    }));
        });
        return done;
    }

    /** Decodes a chest's contents to a full MAX_SIZE array so all stored slots are visible. */
    private ItemStack[] decodeAll(EnderChestData data) {
        if (data.containerData() == null || data.containerData().length == 0) {
            ItemStack[] empty = new ItemStack[ContainerCodec.MAX_SIZE];
            Arrays.fill(empty, ItemStack.empty());
            return empty;
        }
        try {
            return codec.decode(data.containerData(), ContainerCodec.MAX_SIZE);
        } catch (CodecException e) {
            // Abort the spill rather than risk losing items to a bad decode; surfaces as a failure.
            throw new RuntimeException("Codec failure during spill for chest " + data.index(), e);
        }
    }

    /** Highest slot index holding a non-empty item, or -1 if the array is entirely empty. */
    private static int lastUsedSlot(ItemStack[] items) {
        for (int i = items.length - 1; i >= 0; i--) {
            if (!isEmpty(items[i])) return i;
        }
        return -1;
    }

    /** Smallest valid chest size (multiple of 9, 9..54) that holds every non-empty slot in {@code items}. */
    private static int requiredTempSize(ItemStack[] items) {
        int last = lastUsedSlot(items);
        if (last < 0) return ContainerCodec.SLOT_STEP;
        int needed = ((last / ContainerCodec.SLOT_STEP) + 1) * ContainerCodec.SLOT_STEP;
        return Math.max(ContainerCodec.SLOT_STEP, Math.min(ContainerCodec.MAX_SIZE, needed));
    }

    private boolean isInventoryEmpty(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (!isEmpty(item)) return false;
        }
        return true;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    // ---- shutdown ----

    /**
     * Persists every still-open shared session before shutdown. Runs on the disable thread (main /
     * global) with the server stopping, so no viewer can be editing concurrently — encoding the live
     * contents is safe. Each persist registers a pending save that {@link #flushPendingSaves} then waits
     * on.
     */
    private void persistOpenSessions() {
        for (Session s : sessions.values()) {
            if (s.ready && !s.closing) persist(s);
        }
        sessions.clear();
    }

    public void flushPendingSaves() {
        if (pendingSaves.isEmpty()) return;
        try {
            CompletableFuture.allOf(pendingSaves.values().toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Timed out waiting for pending DB saves on shutdown — some data may be lost", e);
        }
    }

    public void shutdown() {
        persistOpenSessions();
        flushPendingSaves();
        // Write-through cache holds no dirty state (every change was persisted immediately), so there
        // is nothing to flush — just drop the references.
        settingsCache.clear();
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
