
package tw.moses.dragonreward;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class DragonReward extends JavaPlugin implements Listener {

    private NamespacedKey frameKey;

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
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        // 定期檢查展示框是否遭意外移除。
        Bukkit.getScheduler().runTaskTimer(this, this::repairFrame, 100L, 100L);

        getLogger().info("DragonReward 已啟動。");
    }

    @Override
    public void onDisable() {
        getLogger().info("DragonReward 已停止。");
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

    // 不使用龍的死亡座標，固定搜尋世界原點。
    Bukkit.getScheduler().runTaskLater(
            this,
            () -> attemptSpawn(world, 1),
            40L
    );
}

private void attemptSpawn(World world, int attempt) {
    if (!isEnabled() || !world.getName().equals(
            getConfig().getString("world", world.getName()))) {
        // 不以舊設定限制新世界；繼續由下面的搜尋判斷。
    }

    // 出口傳送門位於終界中央區域。
    Location center = new Location(world, 0, 0, 0);
    Location portal = findPortal(world, center);

    if (portal == null) {
        retrySpawn(world, attempt, "找不到出口傳送門");
        return;
    }

    Location frameLocation = findFrameLocation(world, portal);

    if (frameLocation == null) {
        retrySpawn(world, attempt, "找不到安全的展示框位置");
        return;
    }

    BlockFace facing = BlockFace.valueOf(
            getConfig().getString("facing", "NORTH")
    );

    // 只有確定能生成時才抽獎，避免每次重試重新抽獎。
    Reward reward = rollReward();

    getConfig().set("world", world.getName());
    getConfig().set("x", frameLocation.getBlockX());
    getConfig().set("y", frameLocation.getBlockY());
    getConfig().set("z", frameLocation.getBlockZ());
    getConfig().set("facing", facing.name());
    getConfig().set("reward", reward.name());
    getConfig().set("claimed", false);
    saveConfig();

    removeTaggedFrames(world);
    createFrame(frameLocation, facing, reward);

    getLogger().info(
            "終界龍獎勵展示框已生成。搜尋次數：" + attempt
                    + "，抽獎結果：" + reward.name()
    );
}

private void retrySpawn(World world, int attempt, String reason) {
    if (attempt >= 15) {
        getLogger().severe(
                "終界龍獎勵生成失敗，已重試 15 次。最後錯誤：" + reason
        );
        return;
    }

    getLogger().warning(
            "第 " + attempt + "/15 次搜尋失敗：" + reason
                    + "，2 秒後重試。"
    );

    Bukkit.getScheduler().runTaskLater(
            this,
            () -> attemptSpawn(world, attempt + 1),
            40L
    );
}


    private Reward rollReward() {
        int roll = random.nextInt(100);

        if (roll < 25) {
            return Reward.ELYTRA;
        } else if (roll < 35) {
            return Reward.DRAGON_EGG;
        } else if (roll < 45) {
            return Reward.DRAGON_HEAD;
        }

        return Reward.NOTHING;
    }

    private Location findPortal(World world, Location origin) {
        Location best = null;
        double bestDistance = Double.MAX_VALUE;

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        for (int x = ox - 16; x <= ox + 16; x++) {
            for (int y = oy - 8; y <= oy + 8; y++) {
                for (int z = oz - 16; z <= oz + 16; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }

                    if (world.getBlockAt(x, y, z).getType()
                            != Material.END_PORTAL) {
                        continue;
                    }

                    double distance =
                            Math.pow(x - ox, 2)
                            + Math.pow(y - oy, 2)
                            + Math.pow(z - oz, 2);

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Location(world, x, y, z);
                    }
                }
            }
        }

        return best;
    }

    private Location findFrameLocation(World world, Location portal) {
        int px = portal.getBlockX();
        int py = portal.getBlockY();
        int pz = portal.getBlockZ();

        // 優先尋找出口傳送門外圍基岩的側面。
        // 不把展示框放在 END_PORTAL 方塊上。
        List<BlockFace> faces = List.of(
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        );

        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    for (int dy = 0; dy <= 2; dy++) {
                        Block support = world.getBlockAt(
                                px + dx, py + dy, pz + dz
                        );

                        if (support.getType() != Material.BEDROCK) {
                            continue;
                        }

                        for (BlockFace face : faces) {
                            Block target = support.getRelative(face);

                            // 只使用空氣格，避開火把、告示牌及其他方塊。
                            if (!target.getType().isAir()) {
                                continue;
                            }

                            // 展示框面向支撐方塊。
                            BlockFace facing = face.getOppositeFace();

                            // 儲存面向方向，供修復時使用。
                            getConfig().set("facing", facing.name());

                            return target.getLocation().add(0.5, 0.5, 0.5);
                        }
                    }
                }
            }
        }

        return null;
    }

    private void createFrame(
            Location location,
            BlockFace facing,
            Reward reward
    ) {
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        // 若同一位置已經有本插件的展示框，不重複生成。
        for (Entity entity : world.getNearbyEntities(
                location, 0.3, 0.3, 0.3)) {
            if (entity instanceof ItemFrame frame
                    && frame.getPersistentDataContainer()
                    .has(frameKey, PersistentDataType.BYTE)) {
                frame.setFacingDirection(facing, true);
                frame.setVisible(true);
                frame.setFixed(true);
                frame.setInvulnerable(true);

                if (!getConfig().getBoolean("claimed", false)) {
                    frame.setItem(new ItemStack(reward.material));
                } else {
                    frame.setItem(null);
                }
                return;
            }
        }

        ItemFrame frame = world.spawn(location, ItemFrame.class, spawned -> {
            spawned.setFacingDirection(facing, true);
            spawned.setVisible(true);
            spawned.setFixed(true);
            spawned.setInvulnerable(true);
            spawned.setPersistent(true);

            spawned.getPersistentDataContainer().set(
                    frameKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            if (reward != Reward.NOTHING
                    && !getConfig().getBoolean("claimed", false)) {
                spawned.setItem(new ItemStack(reward.material));
            }
        });
    }

    private void repairFrame() {
        String worldName = getConfig().getString("world");

        if (worldName == null) {
            return;
        }

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            return;
        }

        Location location = new Location(
                world,
                getConfig().getInt("x") + 0.5,
                getConfig().getInt("y") + 0.5,
                getConfig().getInt("z") + 0.5
        );

        BlockFace facing;
        Reward reward;

        try {
            facing = BlockFace.valueOf(
                    getConfig().getString("facing", "NORTH")
            );
            reward = Reward.valueOf(
                    getConfig().getString("reward", "NOTHING")
            );
        } catch (IllegalArgumentException exception) {
            getLogger().warning("展示框設定值無效，停止自動修復。");
            return;
        }

        for (Entity entity : world.getNearbyEntities(
                location, 0.3, 0.3, 0.3)) {
            if (entity instanceof ItemFrame frame
                    && frame.getPersistentDataContainer()
                    .has(frameKey, PersistentDataType.BYTE)) {
                return;
            }
        }

        // 位置必須仍有基岩支撐，避免方塊變動後生成懸空展示框。
        BlockFace supportFace = facing.getOppositeFace();
        Block support = location.getBlock().getRelative(supportFace);

        if (support.getType() != Material.BEDROCK) {
            return;
        }

        createFrame(location, facing, reward);
    }

    private void removeTaggedFrames(World world) {
        for (Entity entity : world.getEntitiesByClass(ItemFrame.class)) {
            if (entity.getPersistentDataContainer()
                    .has(frameKey, PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
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

        if (getConfig().getBoolean("claimed", false)) {
            return;
        }

        Reward reward;

        try {
            reward = Reward.valueOf(
                    getConfig().getString("reward", "NOTHING")
            );
        } catch (IllegalArgumentException exception) {
            return;
        }

        if (reward == Reward.NOTHING) {
            event.getPlayer().sendMessage("§7這次沒有獲得任何獎勵。");
            getConfig().set("claimed", true);
            saveConfig();
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = new ItemStack(reward.material);

        // 獎勵進入玩家背包，不掉在傳送門洞口。
        var leftovers = player.getInventory().addItem(item);

        if (!leftovers.isEmpty()) {
            leftovers.values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(
                            player.getLocation(), leftover
                    )
            );
        }

        getConfig().set("claimed", true);
        saveConfig();

        frame.setItem(null);
        player.sendMessage("§a你領取了獎勵：§e" + reward.material.name());
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
