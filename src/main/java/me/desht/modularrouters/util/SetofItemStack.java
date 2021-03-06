package me.desht.modularrouters.util;

import gnu.trove.set.hash.TCustomHashSet;
import gnu.trove.strategy.HashingStrategy;
import me.desht.modularrouters.logic.filter.Filter.Flags;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.items.IItemHandler;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class SetofItemStack extends TCustomHashSet<ItemStack> {

    private static class ItemStackHashingStrategy implements HashingStrategy<ItemStack> {
        private final Flags filterFlags;

        public ItemStackHashingStrategy(Flags filterFlags) {
            this.filterFlags = filterFlags;
        }

        @Override
        public int computeHashCode(ItemStack object) {
            int hashCode = Item.getIdFromItem(object.getItem());
            if (!filterFlags.isIgnoreMeta()) hashCode += 37 * object.getItemDamage();
            if (!filterFlags.isIgnoreNBT() && object.hasTagCompound()) hashCode += 37 * object.getTagCompound().hashCode();
            return hashCode;
        }

        @Override
        public boolean equals(ItemStack o1, ItemStack o2) {
            return (o1 == o2) || !(o1 == null || o2 == null)
                    && o1.getItem() == o2.getItem()
                    && (filterFlags.isIgnoreMeta() || o1.getItemDamage() == o2.getItemDamage())
                    && (filterFlags.isIgnoreNBT() || !o1.hasTagCompound() || o1.getTagCompound().equals(o2.getTagCompound()));
        }
    }

    public SetofItemStack(Flags filterFlags) {
        super(new ItemStackHashingStrategy(filterFlags));
    }

    public SetofItemStack(int initialCapacity, Flags filterFlags) {
        super(new ItemStackHashingStrategy(filterFlags), initialCapacity);
    }

    public SetofItemStack(int initialCapacity, float loadFactor, Flags filterFlags) {
        super(new ItemStackHashingStrategy(filterFlags), initialCapacity, loadFactor);
    }

    public SetofItemStack(Collection<? extends ItemStack> collection, Flags filterFlags) {
        super(new ItemStackHashingStrategy(filterFlags), collection);
    }

    public static SetofItemStack fromItemHandler(IItemHandler handler, Flags filterFlags) {
        NonNullList<ItemStack> itemStacks = NonNullList.create();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                itemStacks.add(stack);
            }
        }
        return new SetofItemStack(itemStacks, filterFlags);
    }

    public List<ItemStack> sortedList() {
        return this.stream().sorted(compareStacks).collect(Collectors.toList());
    }

    private static final Comparator<? super ItemStack> compareStacks = (Comparator<ItemStack>) (o1, o2) -> {
        // matches by mod, then by display name
        int c = o1.getItem().getRegistryName().getResourceDomain().compareTo(o2.getItem().getRegistryName().getResourceDomain());
        if (c != 0) return c;
        return o1.getDisplayName().compareTo(o2.getDisplayName());
    };
}
