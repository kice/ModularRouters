package me.desht.modularrouters.gui.module;

import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.container.ContainerModule;
import me.desht.modularrouters.item.module.ItemModule;
import me.desht.modularrouters.item.module.Module;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class ModuleGuiFactory {
    public static GuiModule createGui(EntityPlayer player, EnumHand hand) {
        return createGui(player, player.getHeldItem(hand), null, -1, hand);
    }

    public static GuiModule createGui(EntityPlayer player, ItemStack moduleStack, BlockPos routerPos, int slotIndex) {
        return createGui(player, moduleStack, routerPos, slotIndex, null);
    }

    public static GuiModule createGui(EntityPlayer player, World world, int x, int y, int z) {
        TileEntityItemRouter router = TileEntityItemRouter.getRouterAt(world, new BlockPos(x, y, z));
        if (router != null) {
            int moduleSlotIndex = router.getModuleConfigSlot(player);
            if (moduleSlotIndex >= 0) {
                router.clearConfigSlot(player);
                ItemStack installedModuleStack = router.getModules().getStackInSlot(moduleSlotIndex);
                return installedModuleStack.isEmpty() ? null : ModuleGuiFactory.createGui(player, installedModuleStack, router.getPos(), moduleSlotIndex);
            }
        }
        return null;
    }

    public static Container createContainer(EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        Module module = ItemModule.getModule(stack);
        return module == null ? null : module.createGuiContainer(player, hand, stack, null);
    }

    public static Container createContainer(EntityPlayer player, World world, int x, int y, int z) {
        TileEntityItemRouter router = TileEntityItemRouter.getRouterAt(world, new BlockPos(x, y, z));
        if (router != null) {
            int slotIndex = router.getModuleConfigSlot(player);
            if (slotIndex >= 0) {
                router.clearConfigSlot(player);
                ItemStack installedModuleStack = router.getModules().getStackInSlot(slotIndex);
                Module module = ItemModule.getModule(installedModuleStack);
                return module == null ? null : module.createGuiContainer(player, null, installedModuleStack, router);
            }
        }
        return null;
    }

    private static GuiModule createGui(EntityPlayer player, ItemStack moduleStack, BlockPos routerPos, int slotIndex, EnumHand hand) {
        Module module = ItemModule.getModule(moduleStack);
        if (module == null) {
            return null;
        }
        Class<? extends GuiModule> clazz = module.getGuiHandler();
        try {
            Constructor<? extends GuiModule> ctor = clazz.getConstructor(ContainerModule.class, BlockPos.class, Integer.class, EnumHand.class);
            TileEntityItemRouter router = routerPos == null ? null : TileEntityItemRouter.getRouterAt(player.getEntityWorld(), routerPos);
            return ctor.newInstance(module.createGuiContainer(player, hand, moduleStack, router), routerPos, slotIndex, hand);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            e.printStackTrace();
            return null;
        }
    }
}
