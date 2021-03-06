package me.desht.modularrouters.item.upgrade;

import com.google.common.collect.Sets;
import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.util.ModuleHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.commons.lang3.tuple.Pair;

import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static me.desht.modularrouters.core.RegistrarMR.SOUND_ERROR;
import static me.desht.modularrouters.core.RegistrarMR.SOUND_SUCCESS;

public class SecurityUpgrade extends Upgrade {
    private static final String NBT_PLAYERS = "Players";
    private static final int MAX_PLAYERS = 6;

    @Override
    public void addExtraInformation(ItemStack itemstack, World player, List<String> list, ITooltipFlag advanced) {
        list.add(I18n.format("itemText.security.owner", TextFormatting.YELLOW + getOwnerName(itemstack)));
        Set<String> names = getPlayerNames(itemstack);
        if (!names.isEmpty()) {
            list.add(I18n.format("itemText.security.count", names.size(), MAX_PLAYERS));
            list.addAll(names.stream().map(name -> " \u2022 " + TextFormatting.YELLOW + name).sorted().collect(Collectors.toList()));
        }
    }

    @Override
    public void onCompiled(ItemStack stack, TileEntityItemRouter router) {
        super.onCompiled(stack, router);
        router.addPermittedIds(getPlayerIDs(stack));
    }

    @Override
    public Color getItemTint() {
        return new Color(64, 64, 255);
    }

    private static Set<UUID> getPlayerIDs(ItemStack stack) {
        NBTTagCompound compound = stack.getTagCompound();
        if (compound == null) {
            return Collections.emptySet();
        }

        Set<UUID> res = Sets.newHashSet();
        Pair<String, UUID> owner = ModuleHelper.getOwnerNameAndId(stack);
        res.add(owner.getRight());

        if (compound.hasKey(NBT_PLAYERS)) {
            NBTTagCompound p = compound.getCompoundTag(NBT_PLAYERS);
            res.addAll(p.getKeySet().stream().map(UUID::fromString).collect(Collectors.toList()));
        }
        return res;
    }

    /**
     * Get this security upgrade's owner.
     *
     * @param stack the upgrade itemstack
     * @return (displayable) owner name
     */
    private static String getOwnerName(ItemStack stack) {
        Pair<String, UUID> owner = ModuleHelper.getOwnerNameAndId(stack);
        return owner.getLeft().isEmpty() ? "???" : owner.getLeft();
    }

    /**
     * Get a items of player names added to this security upgrade, not including the owner.
     *
     * @param stack the upgrade itemstack
     * @return set of (displayable) player names
     */
    private static Set<String> getPlayerNames(ItemStack stack) {
        NBTTagCompound compound = stack.getTagCompound();
        if (compound != null && compound.hasKey(NBT_PLAYERS)) {
            NBTTagCompound p = compound.getCompoundTag(NBT_PLAYERS);
            return Sets.newHashSet(p.getKeySet().stream().map(p::getString).sorted().collect(Collectors.toList()));
        } else {
            return Collections.emptySet();
        }
    }

    private static Interacted.Result addPlayer(ItemStack stack, String id, String name) {
        NBTTagCompound compound = stack.getTagCompound();
        if (compound != null) {
            if (!compound.hasKey(NBT_PLAYERS)) {
                compound.setTag(NBT_PLAYERS, new NBTTagCompound());
            }
            NBTTagCompound p = compound.getCompoundTag(NBT_PLAYERS);
            if (p.hasKey(id)) {
                return Interacted.Result.ALREADY_ADDED;  // already there, do nothing
            }
            if (p.getSize() >= MAX_PLAYERS) {
                return Interacted.Result.FULL;  // items full
            }
            p.setString(id, name);
            return Interacted.Result.ADDED;
        }
        return Interacted.Result.ERROR;
    }

    private static Interacted.Result removePlayer(ItemStack stack, String id) {
        NBTTagCompound compound = stack.getTagCompound();
        if (compound != null) {
            if (!compound.hasKey(NBT_PLAYERS)) {
                compound.setTag(NBT_PLAYERS, new NBTTagCompound());
            }
            NBTTagCompound p = compound.getCompoundTag(NBT_PLAYERS);
            if (p.hasKey(id)) {
                p.removeTag(id);
                return Interacted.Result.REMOVED;
            } else {
                return Interacted.Result.NOT_PRESENT;
            }
        }
        return Interacted.Result.ERROR;
    }

    @Mod.EventBusSubscriber
    public static class Interacted {
        enum Result {
            ADDED, REMOVED, FULL, ALREADY_ADDED, ERROR, NOT_PRESENT;

            boolean isError() {
                return this != ADDED && this != REMOVED;
            }
        }

        @SubscribeEvent
        public static void onInteracted(PlayerInteractEvent.RightClickBlock event) {
            EntityPlayer player = event.getEntityPlayer();
            ItemStack stack = player.getHeldItem(event.getHand());
            if (!player.getEntityWorld().isRemote && ItemUpgrade.isType(stack, ItemUpgrade.UpgradeType.SECURITY) && player.isSneaking()) {
                ModuleHelper.setOwner(stack, player);
                player.sendStatusMessage(new TextComponentTranslation("itemText.security.owner", player.getDisplayNameString()), false);
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onInteracted(PlayerInteractEvent.EntityInteract event) {
            if (event.getTarget() instanceof EntityPlayer) {
                ItemStack stack = event.getEntityPlayer().getHeldItem(event.getHand());
                if (ItemUpgrade.isType(stack, ItemUpgrade.UpgradeType.SECURITY)) {
                    EntityPlayer targetPlayer = (EntityPlayer) event.getTarget();
                    String id = targetPlayer.getUniqueID().toString();
                    String name = targetPlayer.getDisplayNameString();
                    Result res = event.getEntityPlayer().isSneaking() ? removePlayer(stack, id) : addPlayer(stack, id, name);
                    if (event.getWorld().isRemote) {
                        event.getEntityPlayer().playSound(res.isError() ? SOUND_ERROR : SOUND_SUCCESS, 1.0f, 1.0f);
                    } else {
                        event.getEntityPlayer().sendStatusMessage(new TextComponentTranslation("chatText.security." + res.toString(), name), false);
                    }
                    event.setCanceled(true);
                }
            }
        }
    }

}
