package me.desht.modularrouters.logic.compiled;

import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.config.ConfigHandler;
import me.desht.modularrouters.item.upgrade.ItemUpgrade;
import me.desht.modularrouters.util.BlockUtil;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public class CompiledBreakerModule extends CompiledModule {
    private final boolean silkTouch;
    private final int fortune;

    public CompiledBreakerModule(TileEntityItemRouter router, ItemStack stack) {
        super(router, stack);

        silkTouch = EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
        fortune = EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, stack);
    }

    @Override
    public boolean execute(TileEntityItemRouter router) {
        if (isRegulationOK(router, true)) {
            World world = router.getWorld();
            if (!(world instanceof WorldServer)) {
                return false;
            }
            BlockPos pos = getTarget().pos;
            int oldId = Block.getStateId(world.getBlockState(pos));
            BlockUtil.BreakResult breakResult = BlockUtil.tryBreakBlock(world, pos, getFilter(), silkTouch, fortune);
            if (breakResult.isBlockBroken()) {
                breakResult.processDrops(world, pos, router.getBuffer());
                if (ConfigHandler.module.breakerParticles && router.getUpgradeCount(ItemUpgrade.UpgradeType.MUFFLER) == 0) {
                    world.playEvent(2001, pos, oldId);
                }
                return true;
            }
        }
        return false;
    }
}
