package com.koletar.jj.mineresetlite;

import com.boydti.fawe.FaweAPI;
import com.boydti.fawe.bukkit.wrapper.AsyncWorld;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.util.TaskManager;
import com.google.common.collect.ImmutableList;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * @author jjkoletar
 */
public class Mine implements ConfigurationSerializable {
    private static final List<Direction> FACES = ImmutableList.copyOf(Direction.values());
    private enum Direction {
        POS_X, POS_Z, NEG_X, NEG_Z
    }
    private static Direction getRandomDir() {
        return FACES.get(ThreadLocalRandom.current().nextInt(FACES.size()));
    }

    private static Location setFacing(Location loc, Location face) {
        loc = loc.clone();

        double dx = face.getX() - loc.getX();
        double dy = face.getY() - loc.getY();
        double dz = face.getZ() - loc.getZ();

        if (dx != 0) {
            if (dx < 0) {
                loc.setYaw((float) (1.5 * Math.PI));
            } else {
                loc.setYaw((float) (0.5 * Math.PI));
            }
            loc.setYaw((float) loc.getYaw() - (float) Math.atan(dz / dx));
        } else if (dz < 0) {
            loc.setYaw((float) Math.PI);
        }

        double dxz = Math.sqrt(Math.pow(dx, 2) + Math.pow(dz, 2));
        loc.setPitch((float) -Math.atan(dy / dxz));

        loc.setYaw(-loc.getYaw() * 180f / (float) Math.PI);
        loc.setPitch(loc.getPitch() * 180f / (float) Math.PI);

        return loc;
    }

    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    private World world;
    private final Map<SerializableBlock, Double> composition;
    private int resetDelay;
    private List<Integer> resetWarnings;
    private String name;
    private SerializableBlock surface;
    private boolean fillMode;
    private int resetClock;
    private boolean isSilent;
    private boolean ignoreLadders = false;
    private int tpX = 0;
    private int tpY = -1;
    private int tpZ = 0;

    public Mine(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String name, World world) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.name = name;
        this.world = world;
        composition = new ConcurrentHashMap<>();
        resetWarnings = new LinkedList<>();
    }

    public Mine(Map<String, Object> me) {
        try {
            minX = (Integer) me.get("minX");
            minY = (Integer) me.get("minY");
            minZ = (Integer) me.get("minZ");
            maxX = (Integer) me.get("maxX");
            maxY = (Integer) me.get("maxY");
            maxZ = (Integer) me.get("maxZ");
        } catch (Throwable t) {
            throw new IllegalArgumentException("Error deserializing coordinate pairs");
        }
        try {
            world = Bukkit.getServer().getWorld((String) me.get("world"));
        } catch (Throwable t) {
            throw new IllegalArgumentException("Error finding world");
        }
        if (world == null) {
            Logger l = Bukkit.getLogger();
            l.severe("[MineResetLite] Unable to find a world! Please include these logger lines along with the stack trace when reporting this bug!");
            l.severe("[MineResetLite] Attempted to load world named: " + me.get("world"));
            l.severe("[MineResetLite] Worlds listed: " + StringTools.buildList(Bukkit.getWorlds(), "", ", "));
            throw new IllegalArgumentException("World was null!");
        }
        try {
            Map<String, Double> sComposition = (Map<String, Double>) me.get("composition");
            composition = new ConcurrentHashMap<>();
            for (Map.Entry<String, Double> entry : sComposition.entrySet()) {
                composition.put(new SerializableBlock(entry.getKey()), entry.getValue());
            }
        } catch (Throwable t) {
            throw new IllegalArgumentException("Error deserializing composition");
        }
        name = (String) me.get("name");
        resetDelay = (Integer) me.get("resetDelay");
        List<String> warnings = (List<String>) me.get("resetWarnings");
        resetWarnings = new LinkedList<Integer>();
        for (String warning : warnings) {
            try {
                resetWarnings.add(Integer.valueOf(warning));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Non-numeric reset warnings supplied");
            }
        }
        if (me.containsKey("surface")) {
            if (!me.get("surface").equals("")) {
                surface = new SerializableBlock((String) me.get("surface"));
            }
        }
        if (me.containsKey("fillMode")) {
            fillMode = (Boolean) me.get("fillMode");
        }
        if (me.containsKey("resetClock")) {
            resetClock = (Integer) me.get("resetClock");
        }
        // Compat for the clock
        if (resetDelay > 0 && resetClock == 0) {
            resetClock = resetDelay;
        }
        if (me.containsKey("isSilent")) {
            isSilent = (Boolean) me.get("isSilent");
        }
        if (me.containsKey("ignoreLadders")) {
            ignoreLadders = (Boolean) me.get("ignoreLadders");
        }
        if (me.containsKey("tpY")) { //Should contain all three if it contains this one
            tpX = (Integer) me.get("tpX");
            tpY = (Integer) me.get("tpY");
            tpZ = (Integer) me.get("tpZ");
        }
    }

    public Map<String, Object> serialize() {
        Map<String, Object> me = new HashMap<String, Object>();
        me.put("minX", minX);
        me.put("minY", minY);
        me.put("minZ", minZ);
        me.put("maxX", maxX);
        me.put("maxY", maxY);
        me.put("maxZ", maxZ);
        me.put("world", world.getName());
        // Make string form of composition
        Map<String, Double> sComposition = new HashMap<String, Double>();
        for (Map.Entry<SerializableBlock, Double> entry : composition.entrySet()) {
            sComposition.put(entry.getKey().toString(), entry.getValue());
        }
        me.put("composition", sComposition);
        me.put("name", name);
        me.put("resetDelay", resetDelay);
        List<String> warnings = new LinkedList<String>();
        for (Integer warning : resetWarnings) {
            warnings.add(warning.toString());
        }
        me.put("resetWarnings", warnings);
        if (surface != null) {
            me.put("surface", surface.toString());
        } else {
            me.put("surface", "");
        }
        me.put("fillMode", fillMode);
        me.put("resetClock", resetClock);
        me.put("isSilent", isSilent);
        me.put("ignoreLadders", ignoreLadders);
        me.put("tpX", tpX);
        me.put("tpY", tpY);
        me.put("tpZ", tpZ);
        return me;
    }

    public boolean getFillMode() {
        return fillMode;
    }

    public void setFillMode(boolean fillMode) {
        this.fillMode = fillMode;
    }

    public List<Integer> getResetWarnings() {
        return resetWarnings;
    }

    public void setResetWarnings(List<Integer> warnings) {
        resetWarnings = warnings;
    }

    public int getResetDelay() {
        return resetDelay;
    }

    public void setResetDelay(int minutes) {
        resetDelay = minutes;
        resetClock = minutes;
    }

    /**
     * Return the length of time until the next automatic reset. The actual
     * length of time is anywhere between n and n-1 minutes.
     *
     * @return clock ticks left until reset
     */
    public int getTimeUntilReset() {
        return resetClock;
    }

    public SerializableBlock getSurface() {
        return surface;
    }

    public void setSurface(SerializableBlock surface) {
        this.surface = surface;
    }

    public World getWorld() {
        return world;
    }

    public String getName() {
        return name;
    }

    public Map<SerializableBlock, Double> getComposition() {
        return composition;
    }

    public double getCompositionTotal() {
        double total = 0;
        for (Double d : composition.values()) {
            total += d;
        }
        return total;
    }

    public int getMinX() {
        return minX;
    }

    public void setMinX(int minX) {
        this.minX = minX;
    }

    public int getMinY() {
        return minY;
    }

    public void setMinY(int minY) {
        this.minY = minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public void setMinZ(int minZ) {
        this.minZ = minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public void setMaxX(int maxX) {
        this.maxX = maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public void setMaxY(int maxY) {
        this.maxY = maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public void setMaxZ(int maxZ) {
        this.maxZ = maxZ;
    }

    public boolean isSilent() {
        return isSilent;
    }

    public void setSilence(boolean isSilent) {
        this.isSilent = isSilent;
    }

    public boolean isIgnoreLadders() {
        return ignoreLadders;
    }

    public void setIgnoreLadders(boolean ignoreLadders) {
        this.ignoreLadders = ignoreLadders;
    }

    public Location getTpPos() {
        return new Location(getWorld(), tpX, tpY, tpZ);
    }

    public void setTpPos(Location l) {
        tpX = l.getBlockX();
        tpY = l.getBlockY();
        tpZ = l.getBlockZ();
    }

    public boolean isInside(Player p) {
        return isInside(p.getLocation());
    }

    public boolean isInside(Location l) {
        return (l.getWorld().getName().equals(getWorld().getName()))
                && (l.getBlockX() >= minX && l.getBlockX() <= maxX) && (l.getBlockY() >= minY && l.getBlockY() <= maxY)
                && (l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ);
    }

    public void reset() {
        reset(null);
    }

    public void reset(Runnable callback) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInside(player)) {
                // Special reset location is configured
                if (tpY >= 0) {
                    player.teleport(getTpPos());
                } else {
                    // Teleport the player to the edge of the mine
                    Location playerLoc = player.getLocation();
                    Location topLoc = new Location(world, playerLoc.getX(), maxY + 2, playerLoc.getZ());
                    Location tpLoc = topLoc.clone();

                    switch (getRandomDir()) {
                        case POS_X:
                            tpLoc.setX(maxX + 2);
                            break;
                        case POS_Z:
                            tpLoc.setZ(maxZ + 2);
                            break;
                        case NEG_X:
                            tpLoc.setX(minX - 2);
                            break;
                        case NEG_Z:
                            tpLoc.setZ(minZ - 2);
                            break;
                    }

                    tpLoc = setFacing(tpLoc, topLoc);
                    player.teleport(tpLoc);
                    player.sendMessage(ChatColor.AQUA + "You were teleported out of the mine while it resets.");
                }
            }
        }

        // Schedule reset task
        TaskManager.IMP.async(() -> {
            AsyncWorld w = AsyncWorld.wrap(world);

            // Calculate probabilities
            List<CompositionEntry> probabilityMap = mapComposition(composition);

            Random rand = new Random();

            FaweQueue queue = FaweAPI.createQueue(world.getName(), true);

            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        if (!fillMode || w.getBlockTypeIdAt(x, y, z) == 0) {
                            if (w.getBlockTypeIdAt(x, y, z) == 65 && ignoreLadders) {
                                continue;
                            }

                            if (y == maxY && surface != null) {
                                queue.setBlock(x, y, z, surface.getBlockId(), surface.getData());
                                continue;
                            }

                            double r = rand.nextDouble();
                            for (CompositionEntry ce : probabilityMap) {
                                if (r <= ce.getChance()) {
                                    queue.setBlock(x, y, z, ce.getBlock().getBlockId(), ce.getBlock().getData());
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (callback != null) {
                queue.addNotifyTask(() -> MineResetLite.instance.doSync(callback));
            }

            queue.enqueue();
        });
    }

    public void cron() {
        if (resetDelay == 0) {
            return;
        }
        if (resetClock > 0) {
            resetClock--; // Tick down to the reset
        }
        if (resetClock == 0) {
            if (!isSilent) {
                MineResetLite.broadcast(Phrases.phrase("mineAutoResetBroadcast", this), this);
            }
            reset(null);
            resetClock = resetDelay;
            return;
        }
        for (Integer warning : resetWarnings) {
            if (warning == resetClock) {
                MineResetLite.broadcast(Phrases.phrase("mineWarningBroadcast", this, warning), this);
            }
        }
    }

    public void teleport(Player player) {
        Location max = new Location(world, Math.max(this.maxX, this.minX), this.maxY, Math.max(this.maxZ, this.minZ));
        Location min = new Location(world, Math.min(this.maxX, this.minX), this.minY, Math.min(this.maxZ, this.minZ));

        Location location = max.add(min).multiply(0.5);
        Block block = location.getBlock();

        if (block.getType() != Material.AIR || block.getRelative(BlockFace.UP).getType() != Material.AIR) {
            location = new Location(world, location.getX(), location.getWorld().getHighestBlockYAt(
                    location.getBlockX(), location.getBlockZ()), location.getZ());
        }

        player.teleport(location);
    }

    public void redefine(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, World world) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.world = world;
    }

    public static ArrayList<CompositionEntry> mapComposition(Map<SerializableBlock, Double> compositionIn) {
        ArrayList<CompositionEntry> probabilityMap = new ArrayList<>();
        Map<SerializableBlock, Double> composition = new HashMap<>(compositionIn);
        double max = 0;
        for (Map.Entry<SerializableBlock, Double> entry : composition.entrySet()) {
            max += entry.getValue();
        }
        // Pad the remaining percentages with air
        if (max < 1d) {
            composition.put(new SerializableBlock(0), 1d - max);
            max = 1d;
        }
        double i = 0;
        for (Map.Entry<SerializableBlock, Double> entry : composition.entrySet()) {
            double v = entry.getValue() / max;
            i += v;
            probabilityMap.add(new CompositionEntry(entry.getKey(), i));
        }
        return probabilityMap;
    }

}
