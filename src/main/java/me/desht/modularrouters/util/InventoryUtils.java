package me.desht.modularrouters.util;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.VanillaDoubleChestItemHandler;

import javax.annotation.Nullable;
import java.util.Random;

public class InventoryUtils {
    /**
     * Drop all items from the given item handler into the world as item entities with random offsets & motions.
     *
     * @param world the world
     * @param pos blockpos to drop at (usually position of the item handler tile entity)
     * @param itemHandler the item handler
     */
    public static void dropInventoryItems(World world, BlockPos pos, IItemHandler itemHandler) {
        Random random = new Random();
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack itemStack = itemHandler.getStackInSlot(i);
            if (!itemStack.isEmpty()) {
                float offsetX = random.nextFloat() * 0.8f + 0.1f;
                float offsetY = random.nextFloat() * 0.8f + 0.1f;
                float offsetZ = random.nextFloat() * 0.8f + 0.1f;
                while (!itemStack.isEmpty()) {
                    int stackSize = Math.min(itemStack.getCount(), random.nextInt(21) + 10);
                    EntityItem entityitem = new EntityItem(world, pos.getX() + (double) offsetX, pos.getY() + (double) offsetY, pos.getZ() + (double) offsetZ, new ItemStack(itemStack.getItem(), stackSize, itemStack.getMetadata()));
                    if (itemStack.hasTagCompound()) {
                        entityitem.getItem().setTagCompound(itemStack.getTagCompound().copy());
                    }
                    itemStack.shrink(stackSize);

                    float motionScale = 0.05f;
                    entityitem.motionX = random.nextGaussian() * (double) motionScale;
                    entityitem.motionY = random.nextGaussian() * (double) motionScale + 0.20000000298023224D;
                    entityitem.motionZ = random.nextGaussian() * (double) motionScale;
                    world.spawnEntity(entityitem);
                }
            }
        }
    }

    /**
     * Get the inventory (item handler) at the given place, from the given side.
     * @param world the world
     * @param pos block position of the item handler TE
     * @param side side to access the TE from (may be null)
     * @return the item handler, or null if there is none
     */
    public static IItemHandler getInventory(World world, BlockPos pos, @Nullable EnumFacing side) {
        // Adapted from Botania's InventoryHelper class (which was in turned adapted from OpenBlocks...)
        TileEntity te = world.getTileEntity(pos);
        if (te == null) {
            return null;
        }

        if (te instanceof TileEntityChest) {
            IItemHandler doubleChest = VanillaDoubleChestItemHandler.get(((TileEntityChest) te));
            if (doubleChest != VanillaDoubleChestItemHandler.NO_ADJACENT_CHESTS_INSTANCE)
                return doubleChest;
        }

        IItemHandler ret = te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side) ?
                te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side) : null;

        if (ret == null && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null))
            ret = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);

        return ret;
    }

    /**
     * Transfer some items from the given slot in the given source item handler to the given destination handler.
     *
     * @param from source item handler
     * @param to destination item handler
     * @param slot slot in the source handler
     * @param count number of items to attempt to transfer
     * @return number of items actually transferred
     */
    public static int transferItems(IItemHandler from, IItemHandler to, int slot, int count) {
        if (from == null || to == null || count == 0) {
            return 0;
        }
        ItemStack toSend = from.extractItem(slot, count, true);
        if (toSend.isEmpty()) {
            return 0;
        }
        ItemStack excess = ItemHandlerHelper.insertItem(to, toSend, false);
        int inserted = toSend.getCount() - excess.getCount();
        from.extractItem(slot, inserted, false);
        return inserted;
    }

    /**
     * Drop an item stack into the world as an item entity.
     *
     * @param world the world
     * @param pos the block position (entity will spawn in centre of block pos)
     * @param stack itemstack to drop
     * @return true if the entity was spawned, false otherwise
     */
    public static boolean dropItems(World world, BlockPos pos, ItemStack stack) {
        if (!world.isRemote) {
            EntityItem item = new EntityItem(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            return world.spawnEntity(item);
        }
        return true;
    }

    /**
     * Get a count of the given item in the given item handler.  NBT data is not considered.
     *
     * @param toCount the item to count
     * @param handler the inventory to check
     * @param max maximum number of items to count
     * @param matchMeta whether or not to consider item metadata
     * @return number of items found, or the supplied max, whichever is smaller
     */
    public static int countItems(ItemStack toCount, IItemHandler handler, int max, boolean matchMeta) {
        int count = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                boolean match = matchMeta ? stack.isItemEqual(toCount) : stack.isItemEqualIgnoreDurability(toCount);
                if (match) {
                    count += stack.getCount();
                }
                if (count >= max) return max;
            }
        }
        return count;
    }
}
