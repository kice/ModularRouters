package me.desht.modularrouters.container;

import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.container.handler.AugmentHandler;
import me.desht.modularrouters.container.handler.BaseModuleHandler.ModuleFilterHandler;
import me.desht.modularrouters.container.slot.BaseModuleSlot.ModuleFilterSlot;
import me.desht.modularrouters.container.slot.ModuleAugmentSlot;
import me.desht.modularrouters.item.augment.Augment;
import me.desht.modularrouters.item.augment.ItemAugment;
import me.desht.modularrouters.logic.filter.Filter;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import static me.desht.modularrouters.container.Layout.SLOT_X_SPACING;
import static me.desht.modularrouters.container.Layout.SLOT_Y_SPACING;

public class ContainerModule extends Container {
    public static final int AUGMENT_START = Filter.FILTER_SIZE;
    static final int INV_START = AUGMENT_START + Augment.SLOTS;
    static final int INV_END = INV_START + 26;
    static final int HOTBAR_START = INV_END + 1;
    static final int HOTBAR_END = HOTBAR_START + 8;

    private static final int PLAYER_INV_Y = 116;
    private static final int PLAYER_INV_X = 16;
    private static final int PLAYER_HOTBAR_Y = PLAYER_INV_Y + 58;

    public final ModuleFilterHandler filterHandler;
    private final AugmentHandler augmentHandler;
    private final int currentSlot;  // currently-selected slot for player
    private final TileEntityItemRouter router;

    public ContainerModule(EntityPlayer player, EnumHand hand, ItemStack moduleStack) {
        this(player, hand, moduleStack, null);
    }

    public ContainerModule(EntityPlayer player, EnumHand hand, ItemStack moduleStack, TileEntityItemRouter router) {
        this.filterHandler = new ModuleFilterHandler(moduleStack);
        this.augmentHandler = new AugmentHandler(moduleStack);
        this.currentSlot = player.inventory.currentItem + HOTBAR_START;
        this.router = router;  // null if module is in player's hand

        // slots for the (ghost) filter items
        for (int i = 0; i < Filter.FILTER_SIZE; i++) {
            ModuleFilterSlot slot = router == null ?
                    new ModuleFilterSlot(filterHandler, player, hand, i, 8 + SLOT_X_SPACING * (i % 3), 17 + SLOT_Y_SPACING * (i / 3)) :
                    new ModuleFilterSlot(filterHandler, router, i, 8 + SLOT_X_SPACING * (i % 3), 17 + SLOT_Y_SPACING * (i / 3));
            addSlotToContainer(slot);
        }

        // slots for the augments
        for (int i = 0; i < Augment.SLOTS; i++) {
            ModuleAugmentSlot slot = router == null ?
                    new ModuleAugmentSlot(augmentHandler, player, hand, i, 78 + SLOT_X_SPACING * (i % 2), 75 + SLOT_Y_SPACING * (i / 2)) :
                    new ModuleAugmentSlot(augmentHandler, router, i, 78 + SLOT_X_SPACING * (i % 2), 75 + SLOT_Y_SPACING * (i / 2));
            addSlotToContainer(slot);
        }

        // player's main inventory - uses default locations for standard inventory texture file
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(new Slot(player.inventory, j + i * 9 + 9, PLAYER_INV_X + j * SLOT_X_SPACING, PLAYER_INV_Y + i * SLOT_Y_SPACING));
            }
        }

        // player's hotbar - uses default locations for standard action bar texture file
        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(player.inventory, i, PLAYER_INV_X + i * SLOT_X_SPACING, PLAYER_HOTBAR_Y));
        }
    }

    protected void transferStackInExtraSlot(EntityPlayer player, int index) {
        // does nothing by default, to be overridden
    }

    protected ItemStack slotClickExtraSlot(int slot, int dragType, ClickType clickTypeIn, EntityPlayer player) {
        // does nothing by default, to be overridden
        return null;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return true;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot srcSlot = inventorySlots.get(index);

        if (srcSlot != null && srcSlot.getHasStack()) {
            if (srcSlot instanceof ModuleFilterSlot) {
                // shift-clicking in a filter slot: clear it from the filter
                srcSlot.putStack(ItemStack.EMPTY);
            } else if (srcSlot instanceof ModuleAugmentSlot) {
                // shift-clicking in augment slots
                ItemStack stackInSlot = srcSlot.getStack();
                if (!mergeItemStack(stackInSlot, INV_START, HOTBAR_END + 1, false)) {
                    return ItemStack.EMPTY;
                }
                srcSlot.onSlotChanged();
                detectAndSendChanges();
            } else if (index >= INV_START && index <= HOTBAR_END) {
                // shift-clicking in player inventory
                ItemStack stackInSlot = srcSlot.getStack();
                if (stackInSlot.getItem() instanceof ItemAugment) {
                    // copy augment items into one of the augment slots if possible
                    if (!mergeItemStack(stackInSlot, AUGMENT_START, AUGMENT_START + Augment.SLOTS, false)) {
                        return ItemStack.EMPTY;
                    }
                    detectAndSendChanges();
                } else {
                    // copy it into the filter (if not already present)
                    // but don't remove it from player inventory
                    ItemStack stack = stackInSlot.copy();
                    stack.setCount(1);
                    int freeSlot;
                    for (freeSlot = 0; freeSlot < Filter.FILTER_SIZE; freeSlot++) {
                        ItemStack stack0 = filterHandler.getStackInSlot(freeSlot);
                        if (stack0.isEmpty() || ItemStack.areItemStacksEqual(stack0, stack)) {
                            break;
                        }
                    }
                    if (freeSlot < Filter.FILTER_SIZE) {
                        inventorySlots.get(freeSlot).putStack(stack);
                        srcSlot.putStack(stackInSlot);
                    }
                }
            } else {
                transferStackInExtraSlot(player, index);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack slotClick(int slot, int dragType, ClickType clickTypeIn, EntityPlayer player) {
//        System.out.println("slotClick: slot=" + slot + ", dragtype=" + dragType + ", clicktype=" + clickTypeIn);
        boolean sendChanges = false;

        if (slot > HOTBAR_END) {
            return slotClickExtraSlot(slot, dragType, clickTypeIn, player);
        }

        switch (clickTypeIn) {
            case PICKUP:
                // normal left-click
                if (router == null && slot == currentSlot) {
                    // no messing with the module that triggered this container's creation
                    return ItemStack.EMPTY;
                }
                if (slot >= 0 && slot < Filter.FILTER_SIZE) {
                    Slot s = inventorySlots.get(slot);
                    ItemStack stackOnCursor = player.inventory.getItemStack();
                    if (!stackOnCursor.isEmpty()) {
                        ItemStack stack1 = stackOnCursor.copy();
                        stack1.setCount(1);
                        s.putStack(stack1);
                    } else {
                        s.putStack(ItemStack.EMPTY);
                    }
                    return ItemStack.EMPTY;
                } else if (slot >= AUGMENT_START && slot < AUGMENT_START + Augment.SLOTS) {
                    sendChanges = true;
                }
            case THROW:
                if (slot >= 0 && slot < Filter.FILTER_SIZE) {
                    return ItemStack.EMPTY;
                } else if (slot >= AUGMENT_START && slot < AUGMENT_START + Augment.SLOTS) {
                    sendChanges = true;
                }
        }
        ItemStack ret = super.slotClick(slot, dragType, clickTypeIn, player);
        if (sendChanges) detectAndSendChanges();
        return ret;
    }
}
