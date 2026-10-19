package dev.dyslexic.cinematic.emu;

import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.client.ClientPayloads;
import dev.dyslexic.cinematic.emu.mixin.AbstractFurnaceMenuAccessor;
import dev.dyslexic.cinematic.emu.mixin.ClientLevelAccessor;
import dev.dyslexic.cinematic.net.AtStationPayload;
import dev.dyslexic.cinematic.net.EnchantDisplayPayload;
import dev.dyslexic.cinematic.net.FurnaceContentsPayload;
import dev.dyslexic.cinematic.net.FurnaceStatePayload;
import dev.dyslexic.cinematic.net.MenuDisplayPayload;
import dev.dyslexic.cinematic.net.MenuFxPayload;
import dev.dyslexic.cinematic.net.SessionClosePayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.net.TableContentsPayload;
import dev.dyslexic.cinematic.station.StationKind;
import dev.dyslexic.cinematic.table.TableGrid;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Stands in for the Cinematic Interactions server half when the server does not have the mod.
 *
 * <p>The mod's client sends {@link StationActionPayload}s and expects display payloads back. Here, each
 * station is backed by the <em>vanilla</em> menu (opened with a normal use-block packet) or the vanilla block
 * interaction, actions become ordinary container clicks, and the display payloads are fed straight into the
 * mod's own client handlers from the client-side menu state.
 */
public final class Emu {
    private static final int OPEN_TIMEOUT = 40;
    private static final int LINGER_TICKS = 25;

    private static Session session;
    private static int now;
    private static PendingClose pendingClose;
    private static final List<Linger> LINGERING = new ArrayList<>();

    private Emu() {
    }

    private static final class Session {
        final BlockPos pos;
        final StationKind kind;
        final Direction side;
        final int born;
        boolean opened;
        int menuId = -1;
        int fingerprint = Integer.MIN_VALUE;
        int stateFingerprint = Integer.MIN_VALUE;
        int reading = -1;

        Session(BlockPos pos, StationKind kind, Direction side, int born) {
            this.pos = pos.immutable();
            this.kind = kind;
            this.side = side;
            this.born = born;
        }
    }

    private record PendingClose(StationKind kind, int until) {
    }

    private record Linger(BlockPos pos, StationKind kind, Direction side, int until) {
    }

    // ------------------------------------------------------------------ state

    /** True when connected to a server that does not have the mod (so we must stand in for it). */
    public static boolean emulating() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.level != null && !ClientPlayNetworking.canSend(StationActionPayload.TYPE);
    }

    public static void reset() {
        session = null;
        pendingClose = null;
        LINGERING.clear();
    }

    private static boolean needsMenu(StationKind kind) {
        return kind.usesMenu() || kind == StationKind.TABLE || kind.isFurnace();
    }

    private static boolean supported(StationKind kind) {
        return kind != StationKind.CAMPFIRE;
    }

    private static boolean isOpen(StationKind kind, AbstractContainerMenu menu) {
        return switch (kind) {
            case TABLE -> menu instanceof CraftingMenu;
            case FURNACE -> menu instanceof FurnaceMenu;
            case SMOKER -> menu instanceof SmokerMenu;
            case BLAST_FURNACE -> menu instanceof BlastFurnaceMenu;
            case ENCHANTING -> menu instanceof EnchantmentMenu;
            case ANVIL -> menu instanceof AnvilMenu;
            case GRINDSTONE -> menu instanceof GrindstoneMenu;
            case SMITHING -> menu instanceof SmithingMenu;
            case STONECUTTER -> menu instanceof StonecutterMenu;
            case LOOM -> menu instanceof LoomMenu;
            case CARTOGRAPHY -> menu instanceof CartographyTableMenu;
            case BREWING -> menu instanceof BrewingStandMenu;
            case CRAFTER -> menu instanceof CrafterMenu;
            case LECTERN -> menu instanceof LecternMenu;
            case BEACON -> menu instanceof BeaconMenu;
            default -> false;
        };
    }

    private static int inputs(StationKind kind) {
        return switch (kind) {
            case ANVIL, GRINDSTONE, CARTOGRAPHY -> 2;
            case SMITHING, LOOM -> 3;
            case STONECUTTER, BEACON -> 1;
            case BREWING -> 5;
            case CRAFTER -> 9;
            default -> 0;
        };
    }

    /** Called by the Minecraft.setScreen mixin: keep the vanilla GUI of an emulated table/furnace session hidden. */
    public static boolean hidesScreen(Screen screen) {
        Session s = session;
        if (s == null || !(screen instanceof AbstractContainerScreen<?> window)) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && needsMenu(s.kind) && window.getMenu() == player.containerMenu && isOpen(s.kind, window.getMenu());
    }

    // ------------------------------------------------------------------ actions from the mod

    public static void handle(StationActionPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            return;
        }
        switch (p.action()) {
            case BEGIN -> begin(mc, player, p);
            case WINDOW -> window(mc, player, p);
            case LEAVE -> {
                if (session != null && session.pos.equals(p.pos()) && session.kind == p.kind()) {
                    end(mc, player);
                }
            }
            default -> {
                if (session != null && session.pos.equals(p.pos()) && session.kind == p.kind()) {
                    act(mc, player, p);
                } else {
                    closeClient();
                }
            }
        }
    }

    private static void closeClient() {
        ClientPayloads.close(new SessionClosePayload());
    }

    private static void begin(Minecraft mc, LocalPlayer player, StationActionPayload p) {
        if (session != null) {
            end(mc, player);
        }
        StationKind kind = p.kind();
        BlockState state = mc.level.getBlockState(p.pos());
        if (!supported(kind) || !kind.matches(state)) {
            closeClient();
            return;
        }
        Direction side = Direction.from2DDataValue(p.side());
        session = new Session(p.pos(), kind, side, now);
        LINGERING.removeIf(l -> l.pos.equals(p.pos()));
        ClientPayloads.atStation(new AtStationPayload(player.getId(), Optional.of(p.pos()), kind));
        if (needsMenu(kind)) {
            useBlock(mc, player, hitFor(mc, p.pos(), side));
        } else {
            session.opened = true;
            refresh(mc, player);
        }
    }

    private static void window(Minecraft mc, LocalPlayer player, StationActionPayload p) {
        if (session != null) {
            end(mc, player);
        }
        BlockState state = mc.level.getBlockState(p.pos());
        if (!p.kind().matches(state) || !supported(p.kind())) {
            return;
        }
        BlockHitResult hit;
        if (p.kind() == StationKind.CHISELED_BOOKSHELF && state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            hit = shelfHit(p.pos(), state.getValue(HorizontalDirectionalBlock.FACING), p.index());
        } else {
            hit = hitFor(mc, p.pos(), Direction.UP);
        }
        useBlock(mc, player, hit);
    }

    private static void act(Minecraft mc, LocalPlayer player, StationActionPayload p) {
        Session s = session;
        if (needsMenu(s.kind) && !s.opened) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        switch (s.kind) {
            case TABLE -> tableAct(mc, player, menu, s, p);
            case FURNACE, SMOKER, BLAST_FURNACE -> {
                if (p.action() == StationActionPayload.Action.CLICK && p.index() >= 0 && p.index() <= 2) {
                    boolean quick = p.shift() && p.button() == 0;
                    click(mc, player, menu, p.index(), p.button(), quick ? ClickType.QUICK_MOVE : ClickType.PICKUP);
                }
            }
            case JUKEBOX -> jukeboxAct(mc, player, s, p);
            case CHISELED_BOOKSHELF -> shelfAct(mc, player, s, p);
            default -> {
                // menu stations: everything but the (cosmetic) CRAFT cue is done through vanilla menu packets by the mod itself
            }
        }
    }

    // ------------------------------------------------------------------ vanilla interaction helpers

    private static void click(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int slot, int button, ClickType type) {
        if (slot >= 0 && slot < menu.slots.size()) {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, player);
        }
    }

    private static BlockHitResult hitFor(Minecraft mc, BlockPos pos, Direction fallback) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 at = ClientBridge.useAt;
        Direction face = fallback;
        if (at != null && at.distanceToSqr(center) < 3.0) {
            Vec3 d = at.subtract(center);
            face = Direction.getNearest(d.x, d.y, d.z);
        } else {
            at = center.add(fallback.getStepX() * 0.5, fallback.getStepY() * 0.5, fallback.getStepZ() * 0.5);
        }
        return new BlockHitResult(at, face, pos, false);
    }

    /** A point on the front face of a chiseled bookshelf that vanilla maps to the given slot. */
    private static BlockHitResult shelfHit(BlockPos pos, Direction facing, int slot) {
        double[] cols = {0.1875, 0.53, 0.84};
        double u = cols[Math.floorMod(slot, 3)];
        double v = slot / 3 == 0 ? 0.75 : 0.25;
        double x;
        double z;
        switch (facing) {
            case NORTH -> { x = 1.0 - u; z = 0.0; }
            case SOUTH -> { x = u; z = 1.0; }
            case WEST -> { x = 0.0; z = u; }
            default -> { x = 1.0; z = 1.0 - u; }
        }
        return new BlockHitResult(new Vec3(pos.getX() + x, pos.getY() + v, pos.getZ() + z), facing, pos, false);
    }

    /** Sends a vanilla use-block packet (the server then opens the menu / performs the block's own interaction). */
    private static void useBlock(Minecraft mc, LocalPlayer player, BlockHitResult hit) {
        ClientPacketListener conn = mc.getConnection();
        ClientLevel level = mc.level;
        if (conn == null || level == null) {
            return;
        }
        // The server skips a block's own use while sneaking with an item in hand; the scene crouches the player.
        boolean sneaking = player.isShiftKeyDown();
        if (sneaking) {
            conn.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }
        try (BlockStatePredictionHandler handler = ((ClientLevelAccessor) level).cinematic$predictions().startPredicting()) {
            conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
        }
        if (sneaking) {
            conn.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        }
    }

    private static Slot inventorySlot(AbstractContainerMenu menu, Inventory inv, int index) {
        for (Slot slot : menu.slots) {
            if (slot.container == inv && slot.getContainerSlot() == index) {
                return slot;
            }
        }
        return null;
    }

    private static void selectHotbar(Minecraft mc, LocalPlayer player, int index) {
        player.getInventory().selected = index;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(index));
    }

    /** Runs {@code action} with the stack from hotbar slot {@code from} (or the cursor stack when -1) in the main hand. */
    private static void withHand(Minecraft mc, LocalPlayer player, int from, Runnable action) {
        Inventory inv = player.getInventory();
        AbstractContainerMenu menu = player.containerMenu;
        int previous = inv.selected;
        int target;
        if (from >= 0 && from < 9) {
            target = from;
        } else if (from < 0) {
            if (menu.getCarried().isEmpty()) {
                return;
            }
            int empty = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).isEmpty()) {
                    empty = i;
                    break;
                }
            }
            Slot slot = empty < 0 ? null : inventorySlot(menu, inv, empty);
            if (slot == null) {
                return;
            }
            click(mc, player, menu, slot.index, 0, ClickType.PICKUP);
            target = empty;
        } else {
            return;
        }
        selectHotbar(mc, player, target);
        action.run();
        selectHotbar(mc, player, previous);
    }

    // ------------------------------------------------------------------ table

    private static int tableSlot(Direction side, int worldCell) {
        for (int gi = 0; gi < 9; gi++) {
            if (TableGrid.worldIndex(side, gi / 3, gi % 3) == worldCell) {
                return 1 + gi;
            }
        }
        return -1;
    }

    private static boolean canAddOne(ItemStack there, ItemStack with) {
        return there.isEmpty() || (ItemStack.isSameItemSameComponents(there, with) && there.getCount() < there.getMaxStackSize());
    }

    private static void tableAct(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Session s, StationActionPayload p) {
        int cell = p.index();
        switch (p.action()) {
            case CLICK -> {
                int slot = tableSlot(s.side, cell);
                if (slot >= 0) {
                    boolean quick = p.shift() && p.button() == 0;
                    click(mc, player, menu, slot, p.button(), quick ? ClickType.QUICK_MOVE : ClickType.PICKUP);
                }
            }
            case PAINT -> placeOneFromCursor(mc, player, menu, tableSlot(s.side, cell));
            case CRAFT -> click(mc, player, menu, 0, 0, p.shift() ? ClickType.QUICK_MOVE : ClickType.PICKUP);
            case PLACE -> {
                int grid = tableSlot(s.side, cell);
                if (grid < 0) {
                    return;
                }
                int from = p.button();
                if (from < 0) {
                    placeOneFromCursor(mc, player, menu, grid);
                    return;
                }
                Slot src = inventorySlot(menu, player.getInventory(), from);
                if (src == null || src.getItem().isEmpty() || !menu.getCarried().isEmpty()
                    || !canAddOne(menu.getSlot(grid).getItem(), src.getItem())) {
                    return;
                }
                click(mc, player, menu, src.index, 0, ClickType.PICKUP);
                click(mc, player, menu, grid, 1, ClickType.PICKUP);
                click(mc, player, menu, src.index, 0, ClickType.PICKUP);
            }
            default -> {
            }
        }
    }

    private static void placeOneFromCursor(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, int grid) {
        ItemStack carried = menu.getCarried();
        if (grid >= 0 && !carried.isEmpty() && canAddOne(menu.getSlot(grid).getItem(), carried)) {
            click(mc, player, menu, grid, 1, ClickType.PICKUP);
        }
    }

    // ------------------------------------------------------------------ jukebox / bookshelf (no menu)

    private static void jukeboxAct(Minecraft mc, LocalPlayer player, Session s, StationActionPayload p) {
        if (p.action() != StationActionPayload.Action.CLICK) {
            return;
        }
        BlockState state = mc.level.getBlockState(s.pos);
        if (!state.hasProperty(JukeboxBlock.HAS_RECORD)) {
            return;
        }
        BlockHitResult hit = hitFor(mc, s.pos, Direction.UP);
        if (state.getValue(JukeboxBlock.HAS_RECORD)) {
            useBlock(mc, player, hit);
            return;
        }
        int from = p.index() == 0 ? -1 : p.button();
        ItemStack source = from < 0 ? player.containerMenu.getCarried()
            : (from < 9 ? player.getInventory().getItem(from) : ItemStack.EMPTY);
        if (source.has(DataComponents.JUKEBOX_PLAYABLE)) {
            withHand(mc, player, from, () -> useBlock(mc, player, hit));
        }
    }

    private static ItemStack shelfBook(Minecraft mc, BlockPos pos, BlockState state, int slot) {
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (be instanceof ChiseledBookShelfBlockEntity shelf && !shelf.getItem(slot).isEmpty()) {
            return shelf.getItem(slot).copy();
        }
        if (state.getBlock() instanceof ChiseledBookShelfBlock
            && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
            return new ItemStack(Items.BOOK);
        }
        return ItemStack.EMPTY;
    }

    private static void shelfCue(LocalPlayer player, Session s, MenuFxPayload.Cue cue, int slot, ItemStack book) {
        ClientPayloads.menuFx(new MenuFxPayload(s.pos, StationKind.CHISELED_BOOKSHELF, player.getId(), cue, slot, List.of(), book));
    }

    private static void shelfAct(Minecraft mc, LocalPlayer player, Session s, StationActionPayload p) {
        int slot = p.index();
        BlockState state = mc.level.getBlockState(s.pos);
        if (slot < 0 || slot > 5 || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        BlockHitResult hit = shelfHit(s.pos, facing, slot);
        ItemStack book = shelfBook(mc, s.pos, state, slot);
        switch (p.action()) {
            case CLICK -> {
                if (!book.isEmpty()) {
                    if (s.reading == slot) {
                        s.reading = -1;
                    }
                    shelfCue(player, s, MenuFxPayload.Cue.PULL, slot, book);
                    useBlock(mc, player, hit);
                }
            }
            case PLACE -> {
                int from = p.button();
                ItemStack source = from < 0 ? player.containerMenu.getCarried()
                    : (from < 9 ? player.getInventory().getItem(from) : ItemStack.EMPTY);
                if (book.isEmpty() && !source.isEmpty() && source.is(ItemTags.BOOKSHELF_BOOKS)) {
                    ItemStack placed = source.copyWithCount(1);
                    shelfCue(player, s, MenuFxPayload.Cue.PUT, slot, placed);
                    withHand(mc, player, from, () -> useBlock(mc, player, hit));
                }
            }
            case CRAFT -> {
                boolean start = p.button() == 1 && !book.isEmpty();
                if (start) {
                    s.reading = slot;
                } else if (s.reading < 0) {
                    return;
                } else {
                    s.reading = -1;
                }
                shelfCue(player, s, start ? MenuFxPayload.Cue.READ : MenuFxPayload.Cue.READ_END, slot, book);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ per-tick work

    public static void tick(Minecraft mc) {
        now++;
        LocalPlayer player = mc.player;
        for (Iterator<Linger> it = LINGERING.iterator(); it.hasNext(); ) {
            Linger l = it.next();
            if (l.until <= now) {
                it.remove();
                clearDisplay(l.pos, l.kind, l.side);
            }
        }
        if (player == null || mc.level == null) {
            return;
        }
        if (pendingClose != null) {
            AbstractContainerMenu menu = player.containerMenu;
            if (menu != player.inventoryMenu && isOpen(pendingClose.kind, menu)) {
                mc.getConnection().send(new ServerboundContainerClosePacket(menu.containerId));
                player.containerMenu = player.inventoryMenu;
                pendingClose = null;
            } else if (pendingClose.until <= now) {
                pendingClose = null;
            }
        }
        Session s = session;
        if (s == null) {
            return;
        }
        if (!emulating()) {
            session = null;
            return;
        }
        if (needsMenu(s.kind)) {
            AbstractContainerMenu menu = player.containerMenu;
            if (!s.opened) {
                if (menu != player.inventoryMenu && isOpen(s.kind, menu)) {
                    s.opened = true;
                    s.menuId = menu.containerId;
                } else {
                    if (now - s.born > OPEN_TIMEOUT) {
                        end(mc, player);
                        closeClient();
                    }
                    return;
                }
            } else if (menu.containerId != s.menuId) {
                // closed by the server, or detached by the mod's own scene teardown
                end(mc, player);
                closeClient();
                return;
            }
        }
        if (!s.kind.matches(mc.level.getBlockState(s.pos))) {
            end(mc, player);
            closeClient();
            return;
        }
        refresh(mc, player);
    }

    private static int hash(ItemStack stack) {
        return stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 7 + stack.getCount();
    }

    private static int hash(List<ItemStack> stacks) {
        int h = 1;
        for (ItemStack stack : stacks) {
            h = h * 31 + hash(stack);
        }
        return h;
    }

    /** Pushes the current block / menu contents into the mod's display caches whenever they change. */
    private static void refresh(Minecraft mc, LocalPlayer player) {
        Session s = session;
        AbstractContainerMenu menu = player.containerMenu;
        int side = s.side.get2DDataValue();
        switch (s.kind) {
            case TABLE -> {
                if (!(menu instanceof CraftingMenu)) {
                    return;
                }
                List<ItemStack> world = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
                byte[] facing = new byte[9];
                boolean any = false;
                for (int gi = 0; gi < 9; gi++) {
                    ItemStack stack = menu.getSlot(1 + gi).getItem();
                    int w = TableGrid.worldIndex(s.side, gi / 3, gi % 3);
                    world.set(w, stack.copy());
                    if (!stack.isEmpty()) {
                        any = true;
                        facing[w] = (byte) side;
                    }
                }
                int fp = hash(world);
                if (fp != s.fingerprint) {
                    s.fingerprint = fp;
                    ClientPayloads.tableContents(new TableContentsPayload(s.pos, any ? world : List.of(), any ? facing : new byte[0]));
                }
            }
            case FURNACE, SMOKER, BLAST_FURNACE -> {
                if (!(menu instanceof AbstractFurnaceMenu)) {
                    return;
                }
                List<ItemStack> items = new ArrayList<>(3);
                boolean any = false;
                for (int i = 0; i < 3; i++) {
                    ItemStack stack = menu.getSlot(i).getItem();
                    any |= !stack.isEmpty();
                    items.add(stack.copy());
                }
                int fp = hash(items);
                if (fp != s.fingerprint) {
                    s.fingerprint = fp;
                    ClientPayloads.furnaceContents(new FurnaceContentsPayload(s.pos, any ? items : List.of()));
                }
                ContainerData data = ((AbstractFurnaceMenuAccessor) menu).cinematic$data();
                int[] v = {data.get(0), data.get(1), data.get(2), data.get(3)};
                int sfp = ((v[0] * 31 + v[1]) * 31 + v[2]) * 31 + v[3];
                if (sfp != s.stateFingerprint) {
                    s.stateFingerprint = sfp;
                    ClientPayloads.furnaceState(new FurnaceStatePayload(s.pos, v[0], v[1], v[2], v[3]));
                }
            }
            case ENCHANTING -> {
                if (!(menu instanceof EnchantmentMenu)) {
                    return;
                }
                ItemStack item = menu.getSlot(0).getItem();
                int lapis = menu.getSlot(1).getItem().getCount();
                int fp = hash(item) * 31 + lapis;
                if (fp != s.fingerprint) {
                    s.fingerprint = fp;
                    ClientPayloads.enchantDisplay(new EnchantDisplayPayload(s.pos, item.copy(), lapis, side));
                }
            }
            case JUKEBOX -> {
                ItemStack record = ItemStack.EMPTY;
                if (mc.level.getBlockEntity(s.pos) instanceof JukeboxBlockEntity jukebox) {
                    record = jukebox.getTheItem().copy();
                }
                BlockState state = mc.level.getBlockState(s.pos);
                if (record.isEmpty() && state.hasProperty(JukeboxBlock.HAS_RECORD) && state.getValue(JukeboxBlock.HAS_RECORD)) {
                    record = new ItemStack(Items.MUSIC_DISC_13);
                }
                int fp = hash(record);
                if (fp != s.fingerprint) {
                    s.fingerprint = fp;
                    ClientPayloads.menuDisplay(new MenuDisplayPayload(s.pos, s.kind, side, List.of(record)));
                }
            }
            case CHISELED_BOOKSHELF -> {
                BlockState state = mc.level.getBlockState(s.pos);
                List<ItemStack> items = new ArrayList<>(6);
                for (int i = 0; i < 6; i++) {
                    items.add(shelfBook(mc, s.pos, state, i));
                }
                int fp = hash(items);
                if (fp != s.fingerprint) {
                    s.fingerprint = fp;
                    ClientPayloads.menuDisplay(new MenuDisplayPayload(s.pos, s.kind, side, items));
                }
            }
            default -> {
                int inputs = inputs(s.kind);
                if (inputs == 0 || !isOpen(s.kind, menu) || now % 2 != 0) {
                    return;
                }
                List<ItemStack> items = new ArrayList<>(inputs);
                for (int i = 0; i < inputs; i++) {
                    items.add(menu.getSlot(i).getItem().copy());
                }
                int fp = hash(items);
                if (fp != s.fingerprint) {
                    s.fingerprint = fp;
                    ClientPayloads.menuDisplay(new MenuDisplayPayload(s.pos, s.kind, side, items));
                }
            }
        }
    }

    // ------------------------------------------------------------------ ending

    private static void end(Minecraft mc, LocalPlayer player) {
        Session s = session;
        session = null;
        if (s == null) {
            return;
        }
        ClientPacketListener conn = mc.getConnection();
        if (needsMenu(s.kind) && conn != null) {
            if (s.opened && s.menuId >= 0) {
                conn.send(new ServerboundContainerClosePacket(s.menuId));
                if (player.containerMenu.containerId == s.menuId) {
                    player.containerMenu = player.inventoryMenu;
                }
            } else {
                // the menu may still be on its way; close it as soon as it shows up
                pendingClose = new PendingClose(s.kind, now + OPEN_TIMEOUT);
            }
        }
        if (s.reading >= 0 && s.kind == StationKind.CHISELED_BOOKSHELF) {
            shelfCue(player, s, MenuFxPayload.Cue.READ_END, s.reading, ItemStack.EMPTY);
        }
        ClientPayloads.atStation(new AtStationPayload(player.getId(), Optional.empty(), s.kind));
        LINGERING.add(new Linger(s.pos, s.kind, s.side, now + LINGER_TICKS));
    }

    private static void clearDisplay(BlockPos pos, StationKind kind, Direction side) {
        int sd = side.get2DDataValue();
        switch (kind) {
            case TABLE -> ClientPayloads.tableContents(new TableContentsPayload(pos, List.of(), new byte[0]));
            case FURNACE, SMOKER, BLAST_FURNACE -> ClientPayloads.furnaceContents(new FurnaceContentsPayload(pos, List.of()));
            case ENCHANTING -> ClientPayloads.enchantDisplay(new EnchantDisplayPayload(pos, ItemStack.EMPTY, 0, sd));
            default -> ClientPayloads.menuDisplay(new MenuDisplayPayload(pos, kind, sd, List.of()));
        }
    }
}
