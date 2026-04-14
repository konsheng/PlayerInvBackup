package org.playerinvbackup.backup.gui.packet;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.MinecraftKey;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedRegistrable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.LoadingHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 基于 ProtocolLib 的“纯发包 GUI”
 *
 * <p>特点:
 * 1) 不使用 Bukkit 的 InventoryClickEvent 体系
 * 2) 菜单打开/刷新/点击全部走数据包
 * 3) 由于服务端没有真实容器, 所有点击包都会被拦截并取消, 再通过重发内容来“回滚”客户端的拖拽/拾取行为
 */
public final class PacketGuiManager {
    private static final int PLAYER_INV_SLOT_COUNT = 36;
    private static final int WINDOW_ID_MIN = 200;
    private static final int WINDOW_ID_RANGE = 50; // 200-249, 避免与常规容器 ID 冲突.
    private static final Object UNRESOLVED_MENU_TYPE = new Object();
    private static final ItemStack EMPTY_ITEM = new ItemStack(Material.AIR);

    private final PlayerInvBackupPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger windowIdCounter = new AtomicInteger(WINDOW_ID_MIN);
    // 缓存已解析的 MenuType, 避免每次打开 GUI 都反射扫描注册表
    private final Map<String, Object> menuTypeHandleCache = new ConcurrentHashMap<>();

    private volatile GuiService guiService;
    private PacketAdapter packetListener;

    private static final class Session {
        private final int windowId;
        private final Inventory top;
        private final Component title;
        private int stateId;

        private Session(int windowId, Inventory top, Component title) {
            this.windowId = windowId;
            this.top = top;
            this.title = title;
        }

        private int nextStateId() {
            stateId++;
            return stateId;
        }
    }

    public PacketGuiManager(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void setGuiService(GuiService guiService) {
        this.guiService = guiService;
    }

    public void register() {
        if (packetListener != null) {
            return;
        }

        packetListener = new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                PacketType.Play.Client.WINDOW_CLICK,
                PacketType.Play.Client.CLOSE_WINDOW
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handlePacketReceiving(event);
            }
        };
        protocolManager.addPacketListener(packetListener);

        // 玩家离线时清理会话, 避免内存泄漏
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                sessions.remove(event.getPlayer().getUniqueId());
            }
        }, plugin);
    }

    public void shutdown() {
        if (packetListener != null) {
            protocolManager.removePacketListener(packetListener);
            packetListener = null;
        }

        // 尽量关闭仍在查看的窗口, 避免客户端残留虚拟菜单
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            UUID uuid = entry.getKey();
            Session session = entry.getValue();
            if (uuid == null || session == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            try {
                sendCloseWindow(player, session.windowId);
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }

    public Component currentTitle(Player player) {
        if (player == null) {
            return null;
        }
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : session.title;
    }

    public Inventory currentTop(Player player) {
        if (player == null) {
            return null;
        }
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : session.top;
    }

    public boolean isViewing(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return false;
        }
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.top == inventory;
    }

    public void openMenu(Player player, Inventory inventory, Component title) {
        if (player == null || inventory == null || title == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        player.getScheduler().run(plugin, ignored -> openMenuNow(player, inventory, title), null);
    }

    private void openMenuNow(Player player, Inventory inventory, Component title) {
        if (player == null || !player.isOnline()) {
            return;
        }

        int size = inventory.getSize();
        if (size <= 0 || size % 9 != 0 || size > 54) {
            plugin.getLogger().warning(plugin.lang().plain(
                    "console.packet-gui.unsupported-size",
                    Placeholder.unparsed("size", String.valueOf(size)),
                    Placeholder.unparsed("player", player.getName())
            ));
            return;
        }

        // 关闭 Bukkit 当前打开的容器, 避免与纯发包 GUI 冲突
        try {
            player.closeInventory();
        } catch (Exception ignored) {
        }

        closeMenuNow(player);

        int windowId = allocateWindowId();
        Session session = new Session(windowId, inventory, title);
        sessions.put(player.getUniqueId(), session);

        if (!sendOpenWindow(player, session)) {
            sessions.remove(player.getUniqueId());
            return;
        }
        sendWindowItems(player, session);
    }

    public void closeMenu(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOnline()) {
            sessions.remove(player.getUniqueId());
            return;
        }
        player.getScheduler().run(plugin, ignored -> closeMenuNow(player), null);
    }

    private void closeMenuNow(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Session removed = sessions.remove(player.getUniqueId());
        if (removed == null) {
            return;
        }
        sendCloseWindow(player, removed.windowId);
    }

    public void syncIfViewing(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> {
            Session session = sessions.get(player.getUniqueId());
            if (session == null || session.top != inventory) {
                return;
            }
            sendWindowItems(player, session);
        }, null);
    }

    public void syncCurrent(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> {
            Session session = sessions.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            sendWindowItems(player, session);
        }, null);
    }

    private int allocateWindowId() {
        int next = windowIdCounter.incrementAndGet();
        int offset = Math.floorMod(next - WINDOW_ID_MIN, WINDOW_ID_RANGE);
        return WINDOW_ID_MIN + offset;
    }

    private void handlePacketReceiving(PacketEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }

        PacketContainer packet = event.getPacket();
        if (packet == null) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            if (!packetContainsWindowId(packet, session.windowId)) {
                return;
            }
            sessions.remove(uuid);
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.WINDOW_CLICK) {
            return;
        }

        if (!packetContainsWindowId(packet, session.windowId)) {
            return;
        }

        // 拦截并取消点击包, 防止客户端移动/拿起物品
        event.setCancelled(true);

        int totalSlots = session.top.getSize() + PLAYER_INV_SLOT_COUNT;
        int clickedSlot = extractClickedSlot(packet, totalSlots);
        if (clickedSlot < -999 || clickedSlot >= totalSlots) {
            clickedSlot = -999;
        }

        int windowId = session.windowId;
        int finalClickedSlot = clickedSlot;
        player.getScheduler().run(plugin, ignored -> handleClickOnPlayerThread(player, windowId, finalClickedSlot), null);
    }

    private void handleClickOnPlayerThread(Player player, int windowId, int clickedSlot) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Session current = sessions.get(player.getUniqueId());
        if (current == null || current.windowId != windowId) {
            return;
        }

        GuiService service = guiService;
        if (service != null && clickedSlot >= 0 && clickedSlot < current.top.getSize()) {
            Object holder = current.top.getHolder();
            if (holder instanceof BackupListHolder listHolder) {
                service.handleListClick(player, listHolder, clickedSlot);
            } else if (holder instanceof BackupViewHolder viewHolder) {
                service.handleViewClick(player, viewHolder, clickedSlot);
            } else if (holder instanceof RestoreConfirmHolder confirmHolder) {
                service.handleRestoreConfirmClick(player, confirmHolder, clickedSlot);
            } else if (holder instanceof LoadingHolder) {
                // Loading 界面不响应点击
            }
        }

        sendWindowItemsIfStill(player, windowId);
    }

    private void sendWindowItemsIfStill(Player player, int windowId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.windowId != windowId) {
            return;
        }
        sendWindowItems(player, session);
    }

    private boolean packetContainsWindowId(PacketContainer packet, int expectedWindowId) {
        if (packet == null) {
            return false;
        }

        try {
            StructureModifier<Integer> ints = packet.getIntegers();
            for (int i = 0; i < ints.size(); i++) {
                Integer value = ints.read(i);
                if (value != null && value == expectedWindowId) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            StructureModifier<Byte> bytes = packet.getBytes();
            for (int i = 0; i < bytes.size(); i++) {
                Byte value = bytes.read(i);
                if (value != null && (value & 0xFF) == expectedWindowId) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            StructureModifier<Short> shorts = packet.getShorts();
            for (int i = 0; i < shorts.size(); i++) {
                Short value = shorts.read(i);
                if (value != null && value == (short) expectedWindowId) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    /**
     * 尽量从 WINDOW_CLICK 包里提取点击的 slot
     *
     * <p>ProtocolLib 会对不同版本做字段适配, 但字段顺序并不保证一致
     * 这里优先走比较稳定的:
     * - 新协议通常 slot 在 Integers[2]
     * - 老协议通常 slot 在 Shorts[0]
     */
    private int extractClickedSlot(PacketContainer packet, int totalSlots) {
        if (packet == null) {
            return -999;
        }

        try {
            StructureModifier<Integer> ints = packet.getIntegers();
            if (ints.size() >= 3) {
                Integer candidate = ints.read(2);
                if (candidate != null && (candidate == -999 || (candidate >= 0 && candidate < totalSlots))) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            StructureModifier<Short> shorts = packet.getShorts();
            if (shorts.size() > 0) {
                Short candidate = shorts.read(0);
                if (candidate != null) {
                    int slot = candidate;
                    if (slot == -999 || (slot >= 0 && slot < totalSlots)) {
                        return slot;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 兜底: 扫描所有 shorts, 取第一个像 slot 的值
        try {
            StructureModifier<Short> shorts = packet.getShorts();
            for (int i = 0; i < shorts.size(); i++) {
                Short candidate = shorts.read(i);
                if (candidate == null) {
                    continue;
                }
                int slot = candidate;
                if (slot == -999 || (slot >= 0 && slot < totalSlots)) {
                    return slot;
                }
            }
        } catch (Exception ignored) {
        }

        // 兜底: 扫描所有 ints, 取第一个像 slot 的值
        try {
            StructureModifier<Integer> ints = packet.getIntegers();
            for (int i = 0; i < ints.size(); i++) {
                Integer candidate = ints.read(i);
                if (candidate == null) {
                    continue;
                }
                int slot = candidate;
                if (slot == -999 || (slot >= 0 && slot < totalSlots)) {
                    return slot;
                }
            }
        } catch (Exception ignored) {
        }

        return -999;
    }

    private boolean sendOpenWindow(Player player, Session session) {
        if (player == null || session == null) {
            return false;
        }
        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.OPEN_WINDOW);

            // window id
            writeWindowId(packet, session.windowId);

            // menu type
            int rows = Math.max(1, Math.min(6, session.top.getSize() / 9));
            String menuKey = "minecraft:generic_9x" + rows;
            if (!writeMenuType(packet, menuKey)) {
                plugin.getLogger().warning(plugin.lang().plain(
                        "console.packet-gui.menu-type-unresolved",
                        Placeholder.unparsed("key", menuKey),
                        Placeholder.unparsed("player", player.getName())
                ));
                return false;
            }

            // title
            writeTitle(packet, session.title);

            protocolManager.sendServerPacket(player, packet);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    plugin.lang().plain(
                            "console.packet-gui.open-packet-failed",
                            Placeholder.unparsed("player", player.getName())
                    ),
                    e
            );
            return false;
        }
    }

    private void sendCloseWindow(Player player, int windowId) {
        if (player == null) {
            return;
        }
        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.CLOSE_WINDOW);
            writeWindowId(packet, windowId);
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception e) {
            plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    plugin.lang().plain(
                            "console.packet-gui.close-packet-failed",
                            Placeholder.unparsed("player", player.getName())
                    ),
                    e
            );
        }
    }

    private void sendWindowItems(Player player, Session session) {
        if (player == null || session == null) {
            return;
        }

        int topSize = session.top.getSize();
        int totalSlots = topSize + PLAYER_INV_SLOT_COUNT;

        List<ItemStack> items = new ArrayList<>(totalSlots);
        ItemStack[] top = session.top.getContents();
        for (int i = 0; i < topSize; i++) {
            ItemStack it = i < top.length ? top[i] : null;
            items.add(safeItem(it));
        }
        items.addAll(buildPlayerInventoryItems(player.getInventory()));

        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.WINDOW_ITEMS);

            // window id + state id (新协议会用到 state id)
            writeWindowId(packet, session.windowId);
            tryWriteStateId(packet, session.nextStateId());

            // items
            if (packet.getItemListModifier().size() > 0) {
                packet.getItemListModifier().write(0, items);
            } else if (packet.getItemArrayModifier().size() > 0) {
                packet.getItemArrayModifier().write(0, items.toArray(ItemStack[]::new));
            }

            // carried item (鼠标上的物品), 强制清空, 防止出现“拿起物品”的假象
            if (packet.getItemModifier().size() > 0) {
                packet.getItemModifier().write(0, new ItemStack(Material.AIR));
            }

            protocolManager.sendServerPacket(player, packet);
        } catch (Exception e) {
            plugin.getLogger().log(
                    Level.WARNING,
                    plugin.lang().plain(
                            "console.packet-gui.refresh-packet-failed",
                            Placeholder.unparsed("player", player.getName())
                    ),
                    e
            );
        }
    }

    private static List<ItemStack> buildPlayerInventoryItems(PlayerInventory inventory) {
        List<ItemStack> out = new ArrayList<>(PLAYER_INV_SLOT_COUNT);
        if (inventory == null) {
            for (int i = 0; i < PLAYER_INV_SLOT_COUNT; i++) {
                out.add(EMPTY_ITEM);
            }
            return out;
        }

        ItemStack[] storage = inventory.getStorageContents();
        if (storage == null || storage.length < PLAYER_INV_SLOT_COUNT) {
            for (int i = 0; i < PLAYER_INV_SLOT_COUNT; i++) {
                out.add(EMPTY_ITEM);
            }
            return out;
        }

        // Chest 容器的排列: 主背包(27, 对应 storage[9..35]) + 快捷栏(9, 对应 storage[0..8])
        for (int i = 9; i < 36; i++) {
            out.add(safeItem(storage[i]));
        }
        for (int i = 0; i < 9; i++) {
            out.add(safeItem(storage[i]));
        }
        return out;
    }

    /**
     * WINDOW_ITEMS(container_set_content) 在 1.21+ 的编码层不接受 null
     * 空槽位必须使用 "empty stack" 表示, 否则会在编码时 NPE 并踢人
     */
    private static ItemStack safeItem(ItemStack item) {
        return item == null ? EMPTY_ITEM : item;
    }

    private void writeWindowId(PacketContainer packet, int windowId) {
        if (packet == null) {
            return;
        }

        try {
            if (packet.getIntegers().size() > 0) {
                packet.getIntegers().write(0, windowId);
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            if (packet.getBytes().size() > 0) {
                packet.getBytes().write(0, (byte) windowId);
            }
        } catch (Exception ignored) {
        }
    }

    private void tryWriteStateId(PacketContainer packet, int stateId) {
        if (packet == null) {
            return;
        }
        try {
            // 一般 WINDOW_ITEMS 的第二个 int 是 stateId
            StructureModifier<Integer> ints = packet.getIntegers();
            if (ints.size() >= 2) {
                ints.write(1, stateId);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean writeMenuType(PacketContainer packet, String menuKey) {
        if (packet == null || menuKey == null || menuKey.isBlank()) {
            return false;
        }

        Class<?> menuTypeClass;
        try {
            menuTypeClass = MinecraftReflection.getMinecraftClass("world.inventory.MenuType");
        } catch (Exception ignored) {
            try {
                menuTypeClass = Class.forName("net.minecraft.world.inventory.MenuType");
            } catch (Exception ignored2) {
                return false;
            }
        }

        // 优先写真实的 MenuType 实例
        // 1.21+ 中, ClientboundOpenScreenPacket 会通过注册表把 MenuType 编码成 id
        // 如果写入的是未注册实例, 会直接导致编码失败并踢人
        Object handle = resolveMenuTypeHandle(menuTypeClass, menuKey);
        if (handle == null) {
            return false;
        }

        try {
            StructureModifier<Object> raw = packet.getModifier().withType(menuTypeClass);
            if (raw != null && raw.size() > 0) {
                raw.write(0, handle);
                return true;
            }
        } catch (Exception ignored) {
        }

        // 兜底 1: 写 Registrable(MenuType)
        try {
            StructureModifier<WrappedRegistrable> modifier = packet.getRegistrableModifier(menuTypeClass);
            if (modifier != null && modifier.size() > 0) {
                modifier.write(0, WrappedRegistrable.fromHandle(menuTypeClass, handle));
                return true;
            }
        } catch (Exception ignored) {
        }

        // 兜底 2: 写 MinecraftKey
        try {
            if (packet.getMinecraftKeys().size() > 0) {
                packet.getMinecraftKeys().write(0, new MinecraftKey(menuKey));
                return true;
            }
        } catch (Exception ignored) {
        }

        // 兜底 3: 写 String (极少数旧协议)
        try {
            if (packet.getStrings().size() > 0) {
                packet.getStrings().write(0, menuKey);
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    /**
     * 根据注册键解析 MenuType 的 NMS 实例
     *
     * <p>优先通过 MenuType 的静态字段(例如 GENERIC_9X3)解析,
     * 如果失败则扫描 BuiltInRegistries, 找到包含该 key 的注册表并取值
     *
     * <p>说明: 这里不能用自造对象或错误的 registrable 包装, 否则会触发 open_screen 的编码异常并踢人
     */
    private Object resolveMenuTypeHandle(Class<?> menuTypeClass, String menuKey) {
        if (menuTypeClass == null || menuKey == null || menuKey.isBlank()) {
            return null;
        }

        Object cached = menuTypeHandleCache.get(menuKey);
        if (cached != null) {
            return cached == UNRESOLVED_MENU_TYPE ? null : cached;
        }

        Object handle = null;
        try {
            // 尝试: MenuType.GENERIC_9X3 这种静态字段
            String path = menuKey.contains(":") ? menuKey.substring(menuKey.indexOf(':') + 1) : menuKey;
            String constantName = path.toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                Field f = null;
                try {
                    f = menuTypeClass.getDeclaredField(constantName);
                } catch (Exception ignored) {
                }
                if (f == null) {
                    // Mojang 映射里可能存在类似 GENERIC_9x3 这种大小写混合字段名, 这里用不区分大小写的方式再兜底一次
                    for (Field candidate : menuTypeClass.getDeclaredFields()) {
                        if (candidate == null) {
                            continue;
                        }
                        if (candidate.getName().equalsIgnoreCase(constantName)) {
                            f = candidate;
                            break;
                        }
                    }
                }
                if (f != null) {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (menuTypeClass.isInstance(v)) {
                        handle = v;
                    }
                }
            } catch (Exception ignored) {
            }

            if (handle == null) {
                // 扫描 BuiltInRegistries 里的所有注册表, 找到能用 key 取到 MenuType 的那一个
                Object nmsKey = createResourceLocation(menuKey);
                if (nmsKey != null) {
                    Class<?> builtInRegistries;
                    try {
                        builtInRegistries = MinecraftReflection.getBuiltInRegistries();
                    } catch (Exception ignored) {
                        builtInRegistries = null;
                    }
                    if (builtInRegistries == null) {
                        try {
                            builtInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                        } catch (Exception ignored) {
                            builtInRegistries = null;
                        }
                    }

                    if (builtInRegistries != null) {
                        // 优先尝试常见字段名
                        Object registry = null;
                        for (String fieldName : List.of("MENU", "MENUS", "MENU_TYPE", "MENU_TYPES")) {
                            try {
                                Field f = builtInRegistries.getDeclaredField(fieldName);
                                f.setAccessible(true);
                                registry = f.get(null);
                                if (registry != null) {
                                    break;
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        if (registry != null) {
                            Object v = tryRegistryLookup(registry, nmsKey, menuTypeClass);
                            if (v != null) {
                                handle = v;
                            }
                        }

                        if (handle == null) {
                            for (Field f : builtInRegistries.getDeclaredFields()) {
                                Object reg;
                                try {
                                    f.setAccessible(true);
                                    reg = f.get(null);
                                } catch (Exception ignored) {
                                    continue;
                                }
                                if (reg == null) {
                                    continue;
                                }
                                Object v = tryRegistryLookup(reg, nmsKey, menuTypeClass);
                                if (v != null) {
                                    handle = v;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            menuTypeHandleCache.put(menuKey, handle == null ? UNRESOLVED_MENU_TYPE : handle);
        }

        return handle;
    }

    private Object tryRegistryLookup(Object registry, Object nmsKey, Class<?> expectedType) {
        if (registry == null || nmsKey == null || expectedType == null) {
            return null;
        }

        Object resourceKey = createRegistryEntryKey(registry, nmsKey);
        List<Object> args = resourceKey == null ? List.of(nmsKey) : List.of(nmsKey, resourceKey);

        // 常见注册表读取方法: get/getOptional/getValue/getHolder
        for (String methodName : List.of("get", "getOptional", "getValue", "getHolder", "getHolderOrThrow")) {
            for (Object arg : args) {
                Object out = tryInvokeSingleArg(registry, methodName, arg);
                out = unwrapOptional(out);
                out = unwrapHolder(out);
                if (expectedType.isInstance(out)) {
                    return out;
                }
            }
        }

        // 兜底: 扫描所有单参数方法, 尝试直接用 key 获取目标类型(避免因实现差异导致方法名不一致)
        for (Method m : registry.getClass().getMethods()) {
            if (m.getParameterCount() != 1 || m.getReturnType() == void.class) {
                continue;
            }
            for (Object arg : args) {
                Object out;
                try {
                    out = m.invoke(registry, arg);
                } catch (Exception ignored) {
                    continue;
                }
                out = unwrapOptional(out);
                out = unwrapHolder(out);
                if (expectedType.isInstance(out)) {
                    return out;
                }
            }
        }
        return null;
    }

    /**
     * 为给定 registry 和 entry 的 ResourceLocation 生成 ResourceKey
     *
     * <p>部分版本/实现的注册表查询方法会要求 ResourceKey 参数, 这里尽量自动推导出可用的 key, 作为 ResourceLocation 的补充兜底
     */
    private Object createRegistryEntryKey(Object registry, Object resourceLocation) {
        if (registry == null || resourceLocation == null) {
            return null;
        }
        Class<?> resourceKeyClass;
        try {
            resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey");
        } catch (Exception ignored) {
            return null;
        }

        Object registryKey = null;
        // 先尝试常见的 key() 方法
        try {
            Method m = registry.getClass().getMethod("key");
            Object v = m.invoke(registry);
            if (resourceKeyClass.isInstance(v)) {
                registryKey = v;
            }
        } catch (Exception ignored) {
        }
        // 再兜底: 扫描所有无参且返回 ResourceKey 的方法
        if (registryKey == null) {
            for (Method m : registry.getClass().getMethods()) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                if (!resourceKeyClass.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                try {
                    Object v = m.invoke(registry);
                    if (resourceKeyClass.isInstance(v)) {
                        registryKey = v;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (registryKey == null) {
            return null;
        }

        // ResourceKey.create(registryKey, location)
        for (Method m : resourceKeyClass.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!m.getName().equals("create") || m.getParameterCount() != 2) {
                continue;
            }
            if (!resourceKeyClass.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            Class<?> p0 = m.getParameterTypes()[0];
            Class<?> p1 = m.getParameterTypes()[1];
            if (!p0.isAssignableFrom(registryKey.getClass())) {
                continue;
            }
            if (!p1.isAssignableFrom(resourceLocation.getClass())) {
                continue;
            }
            try {
                return m.invoke(null, registryKey, resourceLocation);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private Object tryInvokeSingleArg(Object target, String methodName, Object arg) {
        if (target == null || methodName == null || methodName.isBlank() || arg == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(methodName, arg.getClass());
            return m.invoke(target, arg);
        } catch (Exception ignored) {
        }
        // 参数类型不完全一致时(父类/接口), 走 methods 扫描
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName) || m.getParameterCount() != 1) {
                continue;
            }
            try {
                return m.invoke(target, arg);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Object createResourceLocation(String namespacedKey) {
        if (namespacedKey == null || namespacedKey.isBlank()) {
            return null;
        }

        // 优先使用 ProtocolLib 的转换器
        try {
            return MinecraftKey.getConverter().getGeneric(new MinecraftKey(namespacedKey));
        } catch (Exception ignored) {
        }

        // 兜底: 直接反射构造 net.minecraft.resources.ResourceLocation
        Class<?> keyClass;
        try {
            keyClass = MinecraftReflection.getMinecraftKeyClass();
        } catch (Exception ignored) {
            keyClass = null;
        }
        if (keyClass == null) {
            try {
                keyClass = Class.forName("net.minecraft.resources.ResourceLocation");
            } catch (Exception ignored) {
                return null;
            }
        }

        try {
            return keyClass.getConstructor(String.class).newInstance(namespacedKey);
        } catch (Exception ignored) {
        }

        String namespace = "minecraft";
        String path = namespacedKey;
        int idx = namespacedKey.indexOf(':');
        if (idx >= 0) {
            namespace = namespacedKey.substring(0, idx);
            path = namespacedKey.substring(idx + 1);
        }

        try {
            return keyClass.getConstructor(String.class, String.class).newInstance(namespace, path);
        } catch (Exception ignored) {
        }

        for (Method m : keyClass.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != String.class) {
                continue;
            }
            if (!keyClass.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                return m.invoke(null, namespacedKey);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        return value;
    }

    private static Object unwrapHolder(Object value) {
        if (value == null) {
            return null;
        }
        // net.minecraft.core.Holder 在不同映射下方法名可能不同, 这里做最小反射兼容
        try {
            Method m = value.getClass().getMethod("value");
            return m.invoke(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private void writeTitle(PacketContainer packet, Component title) {
        if (packet == null) {
            return;
        }

        Component safe = title == null ? Component.empty() : title;
        String json = GsonComponentSerializer.gson().serialize(safe);
        WrappedChatComponent wrapped = WrappedChatComponent.fromJson(json);

        try {
            if (packet.getChatComponents().size() > 0) {
                packet.getChatComponents().write(0, wrapped);
            }
        } catch (Exception ignored) {
        }
    }
}
