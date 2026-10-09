package cn.lunadeer.dominion.v26_2.nms;

import cn.lunadeer.dominion.nms.FakeEntity;
import cn.lunadeer.dominion.nms.FakeEntityFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;


/**
 * Fake entity factory implementation for Minecraft 26.2.
 */
public class FakeEntityFactoryImpl implements FakeEntityFactory {

    @Override
    public FakeEntity createBlockDisplay(Location location, BlockData blockData) {
        int entityId = Bukkit.getUnsafe().nextEntityId(location.getWorld());
        return new FakeEntityImpl(entityId, location, FakeEntity.DisplayType.BLOCK_DISPLAY, blockData, null);
    }

    @Override
    public FakeEntity createItemDisplay(Location location, ItemStack itemStack) {
        int entityId = Bukkit.getUnsafe().nextEntityId(location.getWorld());
        return new FakeEntityImpl(entityId, location, FakeEntity.DisplayType.ITEM_DISPLAY, null, itemStack);
    }

    @Override
    public int nextEntityId() {
        return Bukkit.getUnsafe().nextEntityId(Bukkit.getWorlds().getFirst());
    }
}
