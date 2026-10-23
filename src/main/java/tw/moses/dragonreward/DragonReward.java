
package tw.moses.dragonreward;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Random;

public final class DragonReward extends JavaPlugin implements Listener {

    private NamespacedKey frameKey;
    private NamespacedKey titleKey;

    private final Random random = new Random();

    private enum Reward {
        ELYTRA(Material.ELYTRA, 25),
        DRAGON_EGG(Material.DRAGON_EGG, 10),
        DRAGON_HEAD(Material.DRAGON_HEAD, 10),
        NOTHING(Material.AIR, 55);

        final Material material;
        final int weight;

        Reward(Material material, int weight) {
            this.material = material;
            this.weight = weight;
        }
    }

    @Override
    public void onEnable() {
        frameKey = new NamespacedKey(this, "dragon_reward_frame");
        titleKey = new NamespacedKey(this, "dragon_reward_title");

        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);

        getCommand("dragonreward").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(
                this,
                this::repairFrame,
                100L,
                100L);

        getLogger().info("DragonReward 已啟動。");
    }

    @Override
    public void onDisable() {
        getLogger().info("DragonReward 已停止。");
    }

    // 管理指令
    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (!sender.hasPermission("dragonreward.admin")) {
            sender.sendMessage("§c你沒有權限使用此指令。");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("§e用法：/dragonreward <reload|test>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage("§aDragonReward 設定檔已重新載入。");
                return true;
            }

            case "test" -> {
                String worldName = getConfig().getString("world");

                if (worldName == null || worldName.isBlank()) {
                    sender.sendMessage("§c設定檔沒有指定世界。");
                    return true;
                }

                World world = Bukkit.getWorld(worldName);

                if (world == null) {
                    sender.sendMessage("§c世界尚未載入：" + worldName);
                    return true;
                }

                int x = getConfig().getInt("x");
                int y = getConfig().getInt("y");
                int z = getConfig().getInt("z");

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    sender.sendMessage("§c設定的 Y 座標超出世界高度。");
                    return true;
                }

                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    sender.sendMessage("§c目標區塊尚未載入，請先前往該座標。");
                    return true;
                }

                BlockFace facing = getFacing();

                if (facing == null) {
                    sender.sendMessage("§cconfig.yml 的 facing 設定無效。");
                    return true;
                }

                Location location = configuredLocation(world);

                // 強制清除本插件的舊展示框與標題。
                removeTaggedEntities(world);

                // 強制抽獎，不檢查目標位置是否為空氣。
                Reward reward = rollReward();

                getConfig().set("reward", reward.name());
                getConfig().set("claimed", false);
                saveConfig();

                createFrame(location, facing, reward);

                sender.sendMessage("§a強制抽獎完成！");
                sender.sendMessage("§e抽中結果：§f" + reward.name());

                getLogger().info("管理員強制抽獎，結果：" + reward.name());
                return true;
            }

            default -> {
                sender.sendMessage("§e用法：/dragonreward <reload|test>");
                return true;
            }
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }

        World world = dragon.getWorld();

        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }

        getLogger().info("偵測到終界龍死亡，2 秒後生成獎勵。");

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> attemptSpawn(world, 1),
                40L);
    }

    private void attemptSpawn(World dragonWorld, int attempt) {
        if (!isEnabled()) {
            return;
        }

        int maxAttempts = Math.max(
                1,
                getConfig().getInt("retry.max-attempts", 15));

        String worldName = getConfig().getString("world");

        if (worldName == null || worldName.isBlank()) {
            retrySpawn(dragonWorld, attempt, "config.yml 未設定 world");
            return;
        }

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            retrySpawn(
                    dragonWorld,
                    attempt,
                    "世界不存在或尚未載入：" + worldName);
            return;
        }

        if (world.getEnvironment() != World.Environment.THE_END) {
            getLogger().severe("設定的世界不是終界：" + worldName);
            return;
        }

        int x = getConfig().getInt("x");
        int y = getConfig().getInt("y");
        int z = getConfig().getInt("z");

        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            retrySpawn(dragonWorld, attempt, "Y 座標超出世界高度");
            return;
        }

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            retrySpawn(dragonWorld, attempt, "目標區塊尚未載入");
            return;
        }

        BlockFace facing = getFacing();

        if (facing == null) {
            getLogger().severe("config.yml 的 facing 設定無效。");
            return;
        }

        Location location = configuredLocation(world);
        Block target = location.getBlock();

        // 支援懸空位置，不要求後方存在基岩或其他支撐方塊。
        //if (!target.isPassable()) {
        //    retrySpawn(
        //            dragonWorld,
        //            attempt,
        //            "目標位置不可通行：" + target.getType());
        //    return;
        //}

        //if (hasTaggedFrame(world, location)) {
        //    getLogger().info("獎勵展示框已存在，不重複生成。");
        //    return;
        //}

        Reward reward = rollReward();

        getConfig().set("world", world.getName());
        getConfig().set("x", x);
        getConfig().set("y", y);
        getConfig().set("z", z);
        getConfig().set("facing", facing.name());
        getConfig().set("reward", reward.name());
        getConfig().set("claimed", false);
        saveConfig();

        removeTaggedEntities(world);
        createFrame(location, facing, reward);

        getLogger().info(
                "獎勵展示框已生成，結果：" + reward.name()
                        + "，嘗試次數：" + attempt + "/" + maxAttempts);
    }

    private void retrySpawn(World world, int attempt, String reason) {
        int maxAttempts = Math.max(
                1,
                getConfig().getInt("retry.max-attempts", 15));

        long delay = Math.max(
                1L,
                getConfig().getLong("retry.delay-ticks", 40L));

        if (attempt >= maxAttempts) {
            getLogger().severe(
                    "生成失敗，已嘗試 " + attempt
                            + " 次。原因：" + reason);
            return;
        }

        getLogger().warning(
                "第 " + attempt + "/" + maxAttempts
                        + " 次生成失敗：" + reason
                        + "；" + (delay / 20.0) + " 秒後重試。");

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> attemptSpawn(world, attempt + 1),
                delay);
    }

    private Reward rollReward() {
        int roll = random.nextInt(100);
        int cumulative = 0;

        for (Reward reward : Reward.values()) {
            cumulative += reward.weight;

            if (roll < cumulative) {
                return reward;
            }
        }

        return Reward.NOTHING;
    }

    private NamedTextColor getTitleColor(Reward reward) {
        return switch (reward) {
            case ELYTRA -> NamedTextColor.RED;
            case DRAGON_EGG -> NamedTextColor.GOLD;
            case DRAGON_HEAD -> NamedTextColor.DARK_PURPLE;
            case NOTHING -> NamedTextColor.GRAY;
        };
    }

    private void createFrame(
            Location location,
            BlockFace facing,
            Reward reward) {
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        world.spawn(location, ItemFrame.class, frame -> {
            frame.setFacingDirection(facing, true);
            frame.setVisible(true);
            frame.setFixed(true);
            frame.setInvulnerable(true);
            frame.setPersistent(true);

            frame.getPersistentDataContainer().set(
                    frameKey,
                    PersistentDataType.BYTE,
                    (byte) 1);

            if (!getConfig().getBoolean("claimed", false)
                    && reward != Reward.NOTHING) {
                frame.setItem(new ItemStack(reward.material));
            }
        });

        // 獨立文字實體，避免依賴 ItemFrame 的名稱渲染。
        createTitleDisplay(location, reward);
    }

    private void createTitleDisplay(Location frameLocation, Reward reward) {
        World world = frameLocation.getWorld();

        if (world == null) {
            return;
        }

        Location textLocation = frameLocation.clone().add(0, 0.8, 0);

        world.spawn(textLocation, TextDisplay.class, text -> {
            text.text(Component.text("擊殺獎勵", getTitleColor(reward)));

            text.setBillboard(Display.Billboard.CENTER);
            text.setAlignment(TextDisplay.TextAlignment.CENTER);
            text.setDefaultBackground(false);
            text.setShadowed(true);
            text.setSeeThrough(false);
            text.setGravity(false);
            text.setInvulnerable(true);
            text.setPersistent(true);

            text.getPersistentDataContainer().set(
                    titleKey,
                    PersistentDataType.BYTE,
                    (byte) 1);
        });
    }

    private Location configuredLocation(World world) {
        return new Location(
                world,
                getConfig().getInt("x") + 0.5,
                getConfig().getInt("y") + 0.5,
                getConfig().getInt("z") + 0.5);
    }

    private BlockFace getFacing() {
        try {
            BlockFace facing = BlockFace.valueOf(
                    getConfig()
                            .getString("facing", "NORTH")
                            .toUpperCase(Locale.ROOT));

            if (facing == BlockFace.NORTH
                    || facing == BlockFace.SOUTH
                    || facing == BlockFace.EAST
                    || facing == BlockFace.WEST) {
                return facing;
            }
        } catch (IllegalArgumentException ignored) {
            // 由呼叫端記錄設定錯誤。
        }

        return null;
    }

    private boolean hasTaggedFrame(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(
                location, 0.5, 0.5, 0.5)) {
            if (entity instanceof ItemFrame frame
                    && frame.getPersistentDataContainer()
                            .has(frameKey, PersistentDataType.BYTE)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasTaggedTitle(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(
                location, 0.5, 1.2, 0.5)) {
            if (entity instanceof TextDisplay text
                    && text.getPersistentDataContainer()
                            .has(titleKey, PersistentDataType.BYTE)) {
                return true;
            }
        }

        return false;
    }

    private void removeTaggedEntities(World world) {
        for (ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
            if (frame.getPersistentDataContainer()
                    .has(frameKey, PersistentDataType.BYTE)) {
                frame.remove();
            }
        }

        removeTaggedTitles(world);
    }

    private void removeTaggedTitles(World world) {
        for (TextDisplay text : world.getEntitiesByClass(TextDisplay.class)) {
            if (text.getPersistentDataContainer()
                    .has(titleKey, PersistentDataType.BYTE)) {
                text.remove();
            }
        }
    }

    private void repairFrame() {
        String worldName = getConfig().getString("world");

        if (worldName == null || !getConfig().contains("x")) {
            return;
        }

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            return;
        }

        int y = getConfig().getInt("y");

        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }

        int x = getConfig().getInt("x");
        int z = getConfig().getInt("z");

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }

        Location location = configuredLocation(world);
        BlockFace facing = getFacing();

        if (facing == null) {
            return;
        }

        Reward reward;

        try {
            reward = Reward.valueOf(
                    getConfig().getString("reward", "NOTHING"));
        } catch (IllegalArgumentException exception) {
            getLogger().warning("獎勵設定無效，無法修復。");
            return;
        }

        if (hasTaggedFrame(world, location)) {
            // 展示框仍在，但文字被刪除時，只補回文字。
            if (!hasTaggedTitle(world, location)) {
                createTitleDisplay(location, reward);
                getLogger().info("已修復擊殺獎勵文字。");
            }

            return;
        }

        if (!location.getBlock().isPassable()) {
            return;
        }

        // 清除殘留的舊標題，避免重複。
        removeTaggedTitles(world);

        createFrame(location, facing, reward);

        getLogger().warning("已修復獎勵展示框及標題。");
    }

    @EventHandler
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        if (!frame.getPersistentDataContainer()
                .has(frameKey, PersistentDataType.BYTE)) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();

        if (getConfig().getBoolean("claimed", false)) {
            player.sendMessage("§7這次獎勵已經領取。");
            return;
        }

        Reward reward;

        try {
            reward = Reward.valueOf(
                    getConfig().getString("reward", "NOTHING"));
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c獎勵設定無效，請通知管理員。");
            return;
        }

        if (reward == Reward.NOTHING) {
            getConfig().set("claimed", true);
            saveConfig();

            frame.setItem(null);
            player.sendMessage("§7很遺憾，這次沒有獲得獎勵。");
            return;
        }

        ItemStack item = new ItemStack(reward.material);

        // 以實際剩餘物品判斷背包空間，避免誤判可堆疊的物品。
        if (!player.getInventory().addItem(item).isEmpty()) {
            player.sendMessage("§c背包已滿，請先清出空間。");
            return;
        }

        getConfig().set("claimed", true);
        saveConfig();

        frame.setItem(null);

        player.sendMessage(
                "§a你領取了獎勵：§e" + reward.material.name());
    }

    @EventHandler
    public void onFrameDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }

        if (frame.getPersistentDataContainer()
                .has(frameKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
