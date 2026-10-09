package cn.lunadeer.validation;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.flag.*;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.nms.*;
import com.google.gson.Gson;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Only creates fixtures and reads actual server state. Never synthesizes Bukkit events. */
public final class UpgradeFixture extends JavaPlugin implements CommandExecutor {
    private final Map<String, Entity> entities = new HashMap<>();
    private final Map<String, FakeEntity> displays = new HashMap<>();
    @Override public void onEnable() {
        if (!Files.exists(Path.of(".dominion-upgrade-validation")) || !Bukkit.getIp().equals("127.0.0.1")) {
            getServer().getPluginManager().disablePlugin(this); return;
        }
        getCommand("upgradeqa").setExecutor(this);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length < 3) return true;
        String token = args[0]; Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) throw new IllegalArgumentException("Player offline");
        player.getScheduler().run(this, task -> {
            Map<String, Object> result = new LinkedHashMap<>();
            try { result.put("value", operate(player, Arrays.copyOfRange(args, 2, args.length))); }
            catch (Throwable error) { result.put("error", error.toString()); error.printStackTrace(); }
            try { Files.writeString(Path.of("qa-" + token + ".json"), new Gson().toJson(result)); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, null);
        return true;
    }
    private Object operate(Player player, String[] args) throws Exception {
        World world = player.getWorld();
        Location at = new Location(world, 100, 80, 102);
        switch (args[0]) {
            case "floor" -> {
                player.setInvulnerable(true);
                for (int x=94; x<=110; x++) for (int z=94; z<=110; z++) {
                    world.getBlockAt(x,79,z).setType(Material.STONE);
                    for (int y=80;y<85;y++) world.getBlockAt(x,y,z).setType(Material.AIR);
                }
                return "floor";
            }
            case "claim" -> {
                var claim = DominionDOO.insert(new DominionDOO(UUID.nameUUIDFromBytes("OfflinePlayer:UpgradeOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "UpgradeClaim", world.getUID(), new CuboidDTO(99,75,102,106,90,108), -1));
                for (PriFlag flag : Flags.getAllPriFlags()) claim.setGuestFlagValue(flag, false);
                claim.setGuestFlagValue(Flags.MOVE,true);
                CacheManager.instance.getCache().getDominionCache().load(claim.getId());
                return claim.getId();
            }
            case "flag" -> {
                var claim = DominionDOO.select("UpgradeClaim");
                var flag = Flags.getFlag(args[1]);
                if (flag instanceof PriFlag privilege) claim.setGuestFlagValue(privilege,Boolean.parseBoolean(args[2]));
                else if (flag instanceof EnvFlag environment) claim.setEnvFlagValue(environment,Boolean.parseBoolean(args[2]));
                else throw new IllegalArgumentException("Unknown flag");
                CacheManager.instance.getCache().getDominionCache().load(claim.getId());
                return args[1]+"="+args[2];
            }
            case "shelf" -> {
                var block=at.getBlock();block.setType(Material.valueOf(args[1]));
                block.setBlockData(Bukkit.createBlockData("minecraft:"+args[1].toLowerCase(java.util.Locale.ROOT)+"[facing=north]"));
                var shelf=(org.bukkit.block.Shelf)block.getState();shelf.getInventory().clear();shelf.getInventory().setItem(1,new ItemStack(Material.DIAMOND,7));
                return "shelf";
            }
            case "shelf-snapshot" -> {return Arrays.stream(((org.bukkit.block.Shelf)at.getBlock().getState()).getInventory().getContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();}
            case "block" -> {
                world.getBlockAt(100,80,101).setType(Material.AIR);
                var block = at.getBlock(); block.setType(Material.valueOf(args[1]));
                if (block.getState() instanceof Container container) { container.getInventory().clear(); container.getInventory().setItem(0,new ItemStack(Material.DIAMOND,7)); }
                return block.getType().name();
            }
            case "double" -> {
                world.getBlockAt(100,80,101).setType(Material.CHEST);
                world.getBlockAt(100,80,101).setBlockData(Bukkit.createBlockData("minecraft:chest[facing=east,type=left]"));
                at.getBlock().setType(Material.CHEST);
                at.getBlock().setBlockData(Bukkit.createBlockData("minecraft:chest[facing=east,type=right]"));
                ((Container) at.getBlock().getState()).getInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
                return ((Container) at.getBlock().getState()).getInventory().getSize();
            }
            case "held" -> { player.getInventory().clear(); player.getInventory().setHeldItemSlot(0); player.getInventory().setItemInMainHand(new ItemStack(Material.valueOf(args[1]),8)); return args[1]; }
            case "spawn" -> {
                Entity previous = entities.remove(args[1]); if (previous != null) previous.remove();
                at.getBlock().setType(Material.AIR);
                Entity entity = world.spawnEntity(at.clone().add(.5,0,.5),EntityType.valueOf(args[1]));
                if (entity instanceof Mob mob) mob.setAI(false);
                if (args.length>2 && entity instanceof LivingEntity living) living.getEquipment().setItem(EquipmentSlot.BODY,new ItemStack(Material.valueOf(args[2])));
                entities.put(args[1],entity); return Map.of("id",entity.getEntityId(),"kind",entity.getType().name());
            }
            case "entity" -> {
                Entity entity = entities.get(args[1]); Map<String,Object> value = new LinkedHashMap<>();
                value.put("valid",entity.isValid()); value.put("id",entity.getEntityId());
                if (entity instanceof LivingEntity living) { value.put("health",living.getHealth()); value.put("body",living.getEquipment().getItem(EquipmentSlot.BODY).getType().name()); }
                return value;
            }
            case "cache" -> {
                var claim=CacheManager.instance.getDominion(at);
                var flag=Flags.getFlag(args[1]);
                if(claim==null)return Map.of("claim","missing");
                return Map.of("claim",claim.getName(),"value",flag instanceof PriFlag p ? claim.getGuestFlagValue(p) : claim.getEnvFlagValue((EnvFlag)flag));
            }
            case "container-snapshot" -> {
                return Map.of("contents",Arrays.stream(((Container)at.getBlock().getState()).getInventory().getContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum(),"cursor",player.getItemOnCursor().getAmount());
            }
            case "snapshot" -> {
                return Map.of("block",at.getBlock().getType().name(),"held",player.getInventory().getItemInMainHand().getType().name(),"amount",player.getInventory().getItemInMainHand().getAmount());
            }
            case "display" -> {
                var factory=NMSManager.instance().getFakeEntityFactory();
                var block=factory.createBlockDisplay(at,Material.SULFUR.createBlockData());
                var item=factory.createItemDisplay(at.clone().add(1,0,0),new ItemStack(Material.SULFUR));
                block.spawn(player);item.spawn(player); displays.put("block",block);displays.put("item",item);
                block.teleport(at.clone().add(0,1,0),player);item.sendMetadata(player);
                return Map.of("block",block.getEntityId(),"item",item.getEntityId());
            }
            case "destroy" -> { for(var display:displays.values())display.destroy(player); displays.clear();return "destroyed"; }
            case "explosion-target" -> {
                world.getBlockAt(101,80,103).setType(Material.DIRT);return "target";
            }
            case "explosion-snapshot" -> {return world.getBlockAt(101,80,103).getType().name();}
            case "ignite" -> { var cube=(SulfurCube)entities.get("SULFUR_CUBE");cube.ignite();cube.setFuseTicks(1);return "ignited"; }
            case "golem" -> {
                var source=at.getBlock();source.setType(Material.COPPER_CHEST);
                var container=(Container)source.getState();container.getInventory().clear();container.getInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
                var golem=world.spawn(new Location(world,100.5,80,100.5),CopperGolem.class);golem.setPersistent(true);
                entities.put("COPPER_GOLEM",golem);return golem.getEntityId();
            }
            case "golem-snapshot" -> { var inventory=((Container)at.getBlock().getState()).getInventory();return Arrays.stream(inventory.getContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum(); }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
