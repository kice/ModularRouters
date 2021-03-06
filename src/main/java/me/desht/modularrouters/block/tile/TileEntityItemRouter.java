package me.desht.modularrouters.block.tile;

import cofh.redstoneflux.api.IEnergyContainerItem;
import com.google.common.collect.Sets;
import me.desht.modularrouters.ModularRouters;
import me.desht.modularrouters.block.BlockItemRouter;
import me.desht.modularrouters.config.ConfigHandler;
import me.desht.modularrouters.container.handler.BufferHandler;
import me.desht.modularrouters.core.RegistrarMR;
import me.desht.modularrouters.integration.redstoneflux.RFIntegration;
import me.desht.modularrouters.integration.tesla.TeslaIntegration;
import me.desht.modularrouters.item.module.DetectorModule.SignalType;
import me.desht.modularrouters.item.module.FluidModule;
import me.desht.modularrouters.item.module.ItemModule;
import me.desht.modularrouters.item.module.Module;
import me.desht.modularrouters.item.upgrade.CamouflageUpgrade;
import me.desht.modularrouters.item.upgrade.ItemUpgrade;
import me.desht.modularrouters.item.upgrade.ItemUpgrade.UpgradeType;
import me.desht.modularrouters.item.upgrade.Upgrade;
import me.desht.modularrouters.logic.RouterRedstoneBehaviour;
import me.desht.modularrouters.logic.compiled.CompiledModule;
import me.desht.modularrouters.util.RFEnergyWrapper;
import net.darkhax.tesla.capability.TeslaCapabilities;
import net.darkhax.tesla.lib.TeslaUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class TileEntityItemRouter extends TileEntity implements ITickable, ICamouflageable {
    public static final int N_MODULE_SLOTS = 9;
    public static final int N_UPGRADE_SLOTS = 5;

    public static final int COMPILE_MODULES = 0x01;
    public static final int COMPILE_UPGRADES = 0x02;

    public static final String NBT_ACTIVE = "Active";
    public static final String NBT_ACTIVE_TIMER = "ActiveTimer";
    public static final String NBT_ECO_MODE = "EcoMode";
    public static final String NBT_SIDES = "Sides";
    public static final String NBT_PERMITTED = "Permitted";
    public static final String NBT_BUFFER = "Buffer";
    public static final String NBT_MODULES = "Modules";
    public static final String NBT_UPGRADES = "Upgrades";
    public static final String NBT_EXTRA = "Extra";
    public static final String NBT_REDSTONE_MODE = "Redstone";
    private static final String NBT_TICK_RATE = "TickRate";
    private static final String NBT_FLUID_TRANSFER_RATE = "FluidTransfer";
    private static final String NBT_ITEMS_PER_TICK = "ItemsPerTick";

    private int counter = 0;
    private int pulseCounter = 0;

    private RouterRedstoneBehaviour redstoneBehaviour = RouterRedstoneBehaviour.ALWAYS;

    private final BufferHandler bufferHandler = new BufferHandler(this);
    private final ItemStackHandler modulesHandler = new RouterItemHandler.ModuleHandler(this);
    private final ItemStackHandler upgradesHandler = new RouterItemHandler.UpgradeHandler(this);

    private final List<CompiledModule> compiledModules = new ArrayList<>();
    private byte recompileNeeded = COMPILE_MODULES | COMPILE_UPGRADES;
    private int tickRate = ConfigHandler.router.baseTickRate;
    private int itemsPerTick = 1;
    private final int[] upgradeCount = new int[UpgradeType.values().length];
    private int totalUpgradeCount;
    private int moduleCount;

    private int fluidTransferRate;  // mB/t
    private int fluidTransferRemainingIn = 0;
    private int fluidTransferRemainingOut = 0;

    // for tracking redstone emission levels for the detector module
    private final int SIDES = EnumFacing.values().length;
    private final int[] redstoneLevels = new int[SIDES];
    private final int[] newRedstoneLevels = new int[SIDES];
    private final SignalType[] signalType = new SignalType[SIDES];
    private final SignalType[] newSignalType = new SignalType[SIDES];
    private boolean canEmit, prevCanEmit; // used if 1 or more detector modules are installed

    // when a player wants to configure an installed module, this tracks the slot
    // number received from the client-side GUI for that player
    private final Map<UUID, Pair<Integer, Integer>> playerToSlot = new HashMap<>();

    private int redstonePower = -1;  // current redstone power (updated via onNeighborChange())
    private int lastPower;  // tracks previous redstone power level for pulse mode
    private boolean active;  // tracks active state of router
    private int activeTimer = 0;  // used in PULSE mode to time out the active state
    private final Set<UUID> permitted = Sets.newHashSet(); // permitted user ID's from security upgrade
    private byte sidesOpen;   // bitmask of which of the 6 sides are currently open
    private boolean ecoMode = false;  // track eco-mode
    private int ecoCounter = ConfigHandler.router.ecoTimeout;
    private boolean hasPulsedModules = false;
    private NBTTagCompound extData;  // extra (persisted) data which various modules can set & read
    private IBlockState camouflage = null;  // block to masquerade as, set by Camo Upgrade
    private int tunedSyncValue = -1; // for synchronisation tuning, set by Sync Upgrade
    private boolean executing;  // are we currently executing modules?

    public TileEntityItemRouter() {
        super();
    }

    @Override
    public ITextComponent getDisplayName() {
        return new TextComponentTranslation("tile.item_router.name");
    }

    public IItemHandler getBuffer() {
        return bufferHandler;
    }

    public IItemHandler getModules() {
        return modulesHandler;
    }

    public IItemHandler getUpgrades() {
        return upgradesHandler;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound compound = new NBTTagCompound();

        compound.setInteger("x", pos.getX());
        compound.setInteger("y", pos.getY());
        compound.setInteger("z", pos.getZ());

        // these fields are needed for WAILA
        NBTTagList list = new NBTTagList();
        for (UUID u : permitted) {
            list.appendTag(new NBTTagString(u.toString()));
        }
        compound.setTag(NBT_PERMITTED, list);
        compound.setInteger(BlockItemRouter.NBT_MODULE_COUNT, getModuleCount());
        for (UpgradeType type : UpgradeType.values()) {
            compound.setInteger(BlockItemRouter.NBT_UPGRADE_COUNT + "." + type, getUpgradeCount(type));
        }

        compound.setByte(NBT_REDSTONE_MODE, (byte) redstoneBehaviour.ordinal());
        compound.setBoolean(NBT_ECO_MODE, ecoMode);
        compound.setInteger(NBT_TICK_RATE, tickRate);
        compound.setInteger(NBT_ITEMS_PER_TICK, itemsPerTick);
        compound.setInteger(NBT_FLUID_TRANSFER_RATE, fluidTransferRate);

        // these fields are needed for rendering
        compound.setBoolean(NBT_ACTIVE, active);
        compound.setByte(NBT_SIDES, sidesOpen);
        if (camouflage != null) {
            CamouflageUpgrade.writeToNBT(compound, camouflage);
        }
        return compound;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        processClientSync(tag);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, getBlockMetadata(), getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        processClientSync(pkt.getNbtCompound());
    }

    private void processClientSync(NBTTagCompound compound) {
        // called client-side
        boolean rerenderNeeded = false;

        NBTTagList l = compound.getTagList(NBT_PERMITTED, Constants.NBT.TAG_STRING);
        permitted.clear();
        for (int i = 0; i < l.tagCount(); i++) {
            permitted.add(UUID.fromString(l.getStringTagAt(i)));
        }
        moduleCount = compound.getInteger(BlockItemRouter.NBT_MODULE_COUNT);
        int mufflers = getUpgradeCount(UpgradeType.MUFFLER);  // 3 muffler upgrades stops the active animation
        for (UpgradeType type : UpgradeType.values()) {
            upgradeCount[type.ordinal()] = compound.getInteger(BlockItemRouter.NBT_UPGRADE_COUNT + "." + type);
        }
        if (mufflers < 3 && getUpgradeCount(UpgradeType.MUFFLER) >= 3 || mufflers >= 3 && getUpgradeCount(UpgradeType.MUFFLER) < 3) {
            rerenderNeeded = true;
        }

        RouterRedstoneBehaviour newRedstoneBehaviour = RouterRedstoneBehaviour.values()[compound.getByte(NBT_REDSTONE_MODE)];
        setRedstoneBehaviour(newRedstoneBehaviour);
        tickRate = compound.getInteger(NBT_TICK_RATE);
        itemsPerTick = compound.getInteger(NBT_ITEMS_PER_TICK);
        fluidTransferRate = compound.getInteger(NBT_FLUID_TRANSFER_RATE);

        // these fields are needed for rendering
        boolean newActive = compound.getBoolean(NBT_ACTIVE);
        byte newSidesOpen = compound.getByte(NBT_SIDES);
        boolean newEco = compound.getBoolean(NBT_ECO_MODE);
        IBlockState camo = CamouflageUpgrade.readFromNBT(compound);
        setActive(newActive);
        setSidesOpen(newSidesOpen);
        setEcoMode(newEco);
        setCamouflage(camo);

        if (rerenderNeeded) {
            getWorld().markBlockRangeForRenderUpdate(pos, pos);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return (oldState.getBlock() != newState.getBlock());
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> cap, EnumFacing side) {
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return true;
        } else if (cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY && bufferHandler.getFluidHandler() != null) {
            return true;
        } else if (hasForgeEnergyCap(cap, getBufferItemStack())) {
            return true;
        } else if (TeslaIntegration.enabled && hasTeslaCap(cap, getBufferItemStack())) {
            return true;
        } else {
            return super.hasCapability(cap, side);
        }
    }

    private boolean hasForgeEnergyCap(Capability<?> cap, ItemStack stack) {
        return cap == CapabilityEnergy.ENERGY &&
                (stack.hasCapability(CapabilityEnergy.ENERGY, null)
                        || RFIntegration.enabled && stack.getItem() instanceof IEnergyContainerItem);
    }

    private boolean hasTeslaCap(Capability<?> cap, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return cap == TeslaCapabilities.CAPABILITY_HOLDER && TeslaUtils.isTeslaHolder(stack, null)
                || cap == TeslaCapabilities.CAPABILITY_PRODUCER && TeslaUtils.isTeslaProducer(stack, null)
                || cap == TeslaCapabilities.CAPABILITY_CONSUMER && TeslaUtils.isTeslaConsumer(stack, null);
    }

    @Nonnull
    @Override
    public <T> T getCapability(@Nonnull Capability<T> cap, @Nullable EnumFacing side) {
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(bufferHandler);
        }
        if (cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(bufferHandler.getFluidHandler());
        }
        ItemStack stack = getBufferItemStack();
        if (!stack.isEmpty()) {
            if (cap == CapabilityEnergy.ENERGY) {
                if (stack.hasCapability(CapabilityEnergy.ENERGY, null)) {
                    return CapabilityEnergy.ENERGY.cast(stack.getCapability(CapabilityEnergy.ENERGY, null));
                } else if (RFIntegration.enabled && stack.getItem() instanceof IEnergyContainerItem) {
                    return CapabilityEnergy.ENERGY.cast(new RFEnergyWrapper(stack));
                }
                // shouldn't get here; if we do, caller is probably in trouble
            }
            if (TeslaIntegration.enabled) {
                if (cap == TeslaCapabilities.CAPABILITY_HOLDER) {
                    return TeslaCapabilities.CAPABILITY_HOLDER.cast(TeslaUtils.getTeslaHolder(stack, null));
                } else if (cap == TeslaCapabilities.CAPABILITY_CONSUMER) {
                    return TeslaCapabilities.CAPABILITY_CONSUMER.cast(TeslaUtils.getTeslaConsumer(stack, null));
                } else if (cap == TeslaCapabilities.CAPABILITY_PRODUCER) {
                    return TeslaCapabilities.CAPABILITY_PRODUCER.cast(TeslaUtils.getTeslaProducer(stack, null));
                }
            }
        }
        return super.getCapability(cap, side);
    }

    private void checkUpgradeSlotCount(NBTTagCompound c) {
        // handle upgrades size change from 4->5 (v1.2.0)
        if (c.getTagId("Size") == Constants.NBT.TAG_INT) {
            int size = c.getInteger("Size");
            if (size < N_UPGRADE_SLOTS) {
                c.setInteger("Size", N_UPGRADE_SLOTS);
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        bufferHandler.deserializeNBT(nbt.getCompoundTag(NBT_BUFFER));
        modulesHandler.deserializeNBT(nbt.getCompoundTag(NBT_MODULES));
        checkUpgradeSlotCount(nbt.getCompoundTag(NBT_UPGRADES));
        upgradesHandler.deserializeNBT(nbt.getCompoundTag(NBT_UPGRADES));
        try {
            redstoneBehaviour = RouterRedstoneBehaviour.valueOf(nbt.getString(NBT_REDSTONE_MODE));
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen...
            redstoneBehaviour = RouterRedstoneBehaviour.ALWAYS;
        }
        active = nbt.getBoolean(NBT_ACTIVE);
        activeTimer = nbt.getInteger(NBT_ACTIVE_TIMER);
        ecoMode = nbt.getBoolean(NBT_ECO_MODE);

        NBTTagCompound ext = nbt.getCompoundTag(NBT_EXTRA);
        NBTTagCompound ext1 = getExtData();
        if (ext != null) {
            for (String key : ext.getKeySet()) {
                ext1.setTag(key, ext.getTag(key));
            }
        }

        // When restoring, give the counter a random initial value to avoid all saved routers
        // having the same counter and firing simultaneously, which could conceivably cause lag
        // spikes if there are many routers in the world.
        // The -1 value indicates that a random value should be picked at the next compile.
        counter = -1;
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt = super.writeToNBT(nbt);
        nbt.setTag(NBT_BUFFER, bufferHandler.serializeNBT());
        nbt.setTag(NBT_MODULES, modulesHandler.serializeNBT());
        nbt.setTag(NBT_UPGRADES, upgradesHandler.serializeNBT());
        nbt.setString(NBT_REDSTONE_MODE, redstoneBehaviour.name());
        nbt.setBoolean(NBT_ACTIVE, active);
        nbt.setInteger(NBT_ACTIVE_TIMER, activeTimer);
        nbt.setBoolean(NBT_ECO_MODE, ecoMode);

        NBTTagCompound ext = new NBTTagCompound();
        NBTTagCompound ext1 = getExtData();
        for (String key : ext1.getKeySet()) {
            ext.setTag(key, ext1.getTag(key));
        }
        nbt.setTag(NBT_EXTRA, ext);

        return nbt;
    }

    @Override
    public void update() {
        if (recompileNeeded != 0) {
            compile();
        }

        if (getWorld().isRemote) {
            return;
        }

        counter++;
        pulseCounter++;

        if (getRedstoneBehaviour() == RouterRedstoneBehaviour.PULSE) {
            // pulse checking is done by checkRedstonePulse() - called from BlockItemRouter#neighborChanged()
            // however, we do need to turn the state inactive after a short time if we were set active by a pulse
            if (activeTimer > 0) {
                if (--activeTimer == 0) {
                    setActive(false);
                }
            }
        } else {
            if (counter >= getTickRate()) {
                allocateFluidTransfer(counter);
                executeModules(false);
                counter = 0;
            }
        }

        if (ecoMode) {
            if (active) {
                ecoCounter = ConfigHandler.router.ecoTimeout;
            } else if (ecoCounter > 0) {
                ecoCounter--;
            }
        }
    }

    private void executeModules(boolean pulsed) {
        executing = true;

        boolean newActive = false;

        boolean powered = pulsed ? true : getRedstonePower() > 0;

        if (redstoneBehaviour.shouldRun(powered, pulsed)) {
            if (prevCanEmit || canEmit) {
                Arrays.fill(newRedstoneLevels, 0);
                Arrays.fill(newSignalType, SignalType.NONE);
            }
            for (CompiledModule cm : compiledModules) {
                if (cm != null && cm.hasTarget() && cm.shouldRun(powered, pulsed) && cm.execute(this)) {
                    newActive = true;
                    if (cm.termination()) {
                        break;
                    }
                }
            }
            if (prevCanEmit || canEmit) {
                handleRedstoneEmission();
            }
        }
        setActive(newActive);
        prevCanEmit = canEmit;
        executing = false;
    }

    public int getTickRate() {
        return ecoMode && ecoCounter == 0 ? ConfigHandler.router.lowPowerTickRate : tickRate;
    }

    public RouterRedstoneBehaviour getRedstoneBehaviour() {
        return redstoneBehaviour;
    }

    public void setRedstoneBehaviour(RouterRedstoneBehaviour redstoneBehaviour) {
        this.redstoneBehaviour = redstoneBehaviour;
        if (redstoneBehaviour == RouterRedstoneBehaviour.PULSE) {
            lastPower = getRedstonePower();
        }
        handleSync(false);
    }

    /**
     * Check if the router processed anything on its last tick
     *
     * @return true if the router processed something
     */
    public boolean isActive() {
        return active;
    }

    private void setActive(boolean newActive) {
        if (active != newActive) {
            active = newActive;
            handleSync(true);
        }
    }

    public boolean isSideOpen(Module.RelativeDirection side) {
        return (sidesOpen & side.getMask()) != 0;
    }

    private void setSidesOpen(byte sidesOpen) {
        if (this.sidesOpen != sidesOpen) {
            this.sidesOpen = sidesOpen;
            handleSync(true);
        }
    }

    public void setEcoMode(boolean newEco) {
        if (newEco != ecoMode) {
            ecoMode = newEco;
            ecoCounter = ConfigHandler.router.ecoTimeout;
            handleSync(false);
        }
    }

    @Override
    public IBlockState getCamouflage() {
        return camouflage;
    }

    @Override
    public void setCamouflage(IBlockState newCamouflage) {
        if (newCamouflage != camouflage) {
            this.camouflage = newCamouflage;
            handleSync(true);
        }
    }

    private void handleSync(boolean renderUpdate) {
        // some tile entity field changed that the client needs to know about
        // if on server, sync TE data to client; if on client, possibly mark the TE for re-render
        if (!getWorld().isRemote) {
            getWorld().notifyBlockUpdate(pos, getWorld().getBlockState(pos), getWorld().getBlockState(pos), 3);
        } else if (getWorld().isRemote && renderUpdate) {
            getWorld().markBlockRangeForRenderUpdate(pos, pos);
        }
    }

    /**
     * Compile installed modules & upgrades etc. into internal data for faster execution
     */
    private void compile() {
        if (getWorld().isRemote) {
            return;
        }

        compileModules();
        compileUpgrades();

        if (tunedSyncValue >= 0) {
            // router has a sync upgrade - init the counter accordingly
            counter = calculateSyncCounter();
        } else if (counter < 0) {
            // we've just restored from NBT - start off with a random counter value
            // to avoid lots of routers all ticking at the same time
            counter = new Random().nextInt(tickRate);
        }

        if (recompileNeeded != 0) {
            IBlockState state = getWorld().getBlockState(pos);
            getWorld().notifyBlockUpdate(pos, state, state, 3);
            getWorld().notifyNeighborsOfStateChange(pos, state.getBlock(), true);
            markDirty();
            recompileNeeded = 0;
        }
    }

    private void compileModules() {
        if ((recompileNeeded & COMPILE_MODULES) != 0) {
            setHasPulsedModules(false);
            byte newSidesOpen = 0;
            for (CompiledModule cm : compiledModules) {
                cm.cleanup(this);
            }
            compiledModules.clear();
            for (int i = 0; i < N_MODULE_SLOTS; i++) {
                ItemStack stack = modulesHandler.getStackInSlot(i);
                Module m = ItemModule.getModule(stack);
                if (m != null) {
                    CompiledModule cms = m.compile(this, stack);
                    compiledModules.add(cms);
                    cms.onCompiled(this);
                    newSidesOpen |= cms.getDirection().getMask();
                }
            }
            moduleCount = compiledModules.size();
            setSidesOpen(newSidesOpen);
        }
    }

    private void compileUpgrades() {
        if ((recompileNeeded & COMPILE_UPGRADES) != 0) {
            Arrays.fill(upgradeCount, 0);
            totalUpgradeCount = 0;
            permitted.clear();
            setCamouflage(null);
            tunedSyncValue = -1;
            for (int i = 0; i < N_UPGRADE_SLOTS; i++) {
                ItemStack stack = upgradesHandler.getStackInSlot(i);
                Upgrade upgrade = ItemUpgrade.getUpgrade(stack);
                if (upgrade != null) {
                    upgradeCount[stack.getItemDamage()] += stack.getCount();
                    totalUpgradeCount += stack.getCount();
                    upgrade.onCompiled(stack, this);
                }
            }

            itemsPerTick = 1 << (Math.min(6, getUpgradeCount(UpgradeType.STACK)));
            tickRate = Math.max(ConfigHandler.router.hardMinTickRate,
                    ConfigHandler.router.baseTickRate - ConfigHandler.router.ticksPerUpgrade * getUpgradeCount(UpgradeType.SPEED));
            fluidTransferRate = Math.min(ConfigHandler.router.fluidMaxTransferRate,
                    ConfigHandler.router.fluidBaseTransferRate + getUpgradeCount(UpgradeType.FLUID) * ConfigHandler.router.mBperFluidUpgrade);
        }
    }

    public void setTunedSyncValue(int newValue) {
        tunedSyncValue = newValue;
    }

    private int calculateSyncCounter() {
        // use the current (total) world time and router's tick rate to determine a value
        // for the tick counter that ensures the router always executes at a certain time
        int compileTime = (int) (getWorld().getTotalWorldTime() % tickRate);
        int tuning = tunedSyncValue % tickRate;
        int delta = tuning - compileTime;
        if (delta <= 0) delta += tickRate;

        return tickRate - delta;
    }

    public void setAllowRedstoneEmission(boolean allow) {
        canEmit = allow;
        getWorld().setBlockState(pos, getWorld().getBlockState(pos).withProperty(BlockItemRouter.CAN_EMIT, canEmit));
    }

    public int getModuleCount() {
        return moduleCount;
    }

    public int getUpgradeCount() {
        return totalUpgradeCount;
    }

    public int getUpgradeCount(UpgradeType type) {
        return upgradeCount[type.ordinal()];
    }

    public void recompileNeeded(int what) {
        recompileNeeded |= what;
    }

    public int getItemsPerTick() {
        return itemsPerTick;
    }

    private void allocateFluidTransfer(int ticks) {
        // increment the in/out fluid transfer allowance based on the number of ticks which have passed
        // and the current fluid transfer rate of the router (which depends on the number of fluid upgrades)
        int maxTransfer = ConfigHandler.router.baseTickRate * fluidTransferRate;
        fluidTransferRemainingIn = Math.min(fluidTransferRemainingIn + ticks * fluidTransferRate, maxTransfer);
        fluidTransferRemainingOut = Math.min(fluidTransferRemainingOut + ticks * fluidTransferRate, maxTransfer);
    }

    public int getFluidTransferRate() {
        return fluidTransferRate;
    }

    public int getCurrentFluidTransferAllowance(FluidModule.FluidDirection dir) {
        return dir == FluidModule.FluidDirection.IN ? fluidTransferRemainingIn : fluidTransferRemainingOut;
    }

    public void transferredFluid(int amount, FluidModule.FluidDirection dir) {
        switch (dir) {
            case IN:
                if (fluidTransferRemainingIn < amount) ModularRouters.logger.warn("fluid transfer: " + fluidTransferRemainingIn + " < " + amount);
                fluidTransferRemainingIn = Math.max(0, fluidTransferRemainingIn - amount);
                break;
            case OUT:
                if (fluidTransferRemainingOut < amount) ModularRouters.logger.warn("fluid transfer: " + fluidTransferRemainingOut + " < " + amount);
                fluidTransferRemainingOut = Math.max(0, fluidTransferRemainingOut - amount);
                break;
            default:
                break;
        }
    }

    public EnumFacing getAbsoluteFacing(Module.RelativeDirection direction) {
        IBlockState state = getWorld().getBlockState(pos);
        return direction.toEnumFacing(state.getValue(BlockItemRouter.FACING));
    }

    public ItemStack getBufferItemStack() {
        return bufferHandler.getStackInSlot(0);
    }

    public void playerConfiguringModule(EntityPlayer player, int slotIndex, int filterIndex) {
        if (slotIndex >= 0) {
            playerToSlot.put(player.getUniqueID(), Pair.of(slotIndex, filterIndex));
        } else {
            playerToSlot.remove(player.getUniqueID());
        }
    }

    public void playerConfiguringModule(EntityPlayer player, int slotIndex) {
        playerConfiguringModule(player, slotIndex, -1);
    }

    public void clearConfigSlot(EntityPlayer player) {
        playerToSlot.remove(player.getUniqueID());
    }

    public int getModuleConfigSlot(EntityPlayer player) {
        if (playerToSlot.containsKey(player.getUniqueID())) {
            return playerToSlot.get(player.getUniqueID()).getLeft();
        } else {
            return -1;
        }
    }

    public int getFilterConfigSlot(EntityPlayer player) {
        if (playerToSlot.containsKey(player.getUniqueID())) {
            return playerToSlot.get(player.getUniqueID()).getRight();
        } else {
            return -1;
        }
    }

    public void checkForRedstonePulse() {
        redstonePower = getWorld().isBlockIndirectlyGettingPowered(pos);
        if (executing) {
            return;  // avoid recursion from executing module triggering more block updates
        }
        if (redstoneBehaviour == RouterRedstoneBehaviour.PULSE
                || hasPulsedModules && redstoneBehaviour == RouterRedstoneBehaviour.ALWAYS) {
            if (redstonePower > lastPower && pulseCounter >= tickRate) {
                allocateFluidTransfer(Math.min(pulseCounter, ConfigHandler.router.baseTickRate));
                executeModules(true);
                pulseCounter = 0;
                if (active) {
                    activeTimer = tickRate;
                }
            }
            lastPower = redstonePower;
        }
    }

    public void emitRedstone(Module.RelativeDirection direction, int power, SignalType signalType) {
        if (direction == Module.RelativeDirection.NONE) {
            Arrays.fill(newRedstoneLevels, power);
            Arrays.fill(newSignalType, signalType);
        } else {
            EnumFacing facing = getAbsoluteFacing(direction).getOpposite();
            newRedstoneLevels[facing.ordinal()] = power;
            newSignalType[facing.ordinal()] = signalType;
        }
    }

    public int getRedstoneLevel(EnumFacing facing, boolean strong) {
        if (!canEmit) {
            // -1 means the block shouldn't have any special redstone handling
            return -1;
        }
        int i = facing.ordinal();
        if (strong) {
            return signalType[i] == SignalType.STRONG ? redstoneLevels[i] : 0;
        } else {
            return signalType[i] != SignalType.NONE ? redstoneLevels[i] : 0;
        }
    }

    private void handleRedstoneEmission() {
        boolean notifyOwnNeighbours = false;
        EnumSet<EnumFacing> toNotify = EnumSet.noneOf(EnumFacing.class);

        if (!canEmit) {
            // block has stopped being able to emit a signal (all detector modules removed)
            // notify neighbours, and neighbours of neighbours where a strong signal was being emitted
            notifyOwnNeighbours = true;
            for (EnumFacing f : EnumFacing.values()) {
                if (signalType[f.ordinal()] == SignalType.STRONG) {
                    toNotify.add(f.getOpposite());
                }
            }
            Arrays.fill(redstoneLevels, 0);
            Arrays.fill(signalType, SignalType.NONE);
        } else {
            for (EnumFacing facing : EnumFacing.values()) {
                int i = facing.ordinal();
                // if the signal op (strong/weak) has changed, notify neighbours of block in that direction
                // if the signal strength has changed, notify immediate neighbours
                //   - and if signal op is strong, also notify neighbours of neighbour
                if (newSignalType[i] != signalType[i]) {
                    toNotify.add(facing.getOpposite());
                    signalType[i] = newSignalType[i];
                }
                if (newRedstoneLevels[i] != redstoneLevels[i]) {
                    notifyOwnNeighbours = true;
                    if (newSignalType[i] == SignalType.STRONG) {
                        toNotify.add(facing.getOpposite());
                    }
                    redstoneLevels[i] = newRedstoneLevels[i];
                }
            }
        }

        for (EnumFacing f : toNotify) {
            BlockPos pos2 = pos.offset(f);
            getWorld().notifyNeighborsOfStateChange(pos2, getWorld().getBlockState(pos2).getBlock(), true);
        }
        if (notifyOwnNeighbours) {
            getWorld().notifyNeighborsOfStateChange(pos, getWorld().getBlockState(pos).getBlock(), true);
        }
    }

    public void addPermittedIds(Set<UUID> permittedIds) {
        this.permitted.addAll(permittedIds);
    }

    public boolean isPermitted(EntityPlayer player) {
        if (permitted.isEmpty() || permitted.contains(player.getUniqueID())) {
            return true;
        }
        for (EnumHand hand : EnumHand.values()) {
            if (player.getHeldItem(hand) != null && player.getHeldItem(hand).getItem() == RegistrarMR.OVERRIDE_CARD) {
                return true;
            }
        }
        return false;
    }

    public boolean isBufferFull() {
        ItemStack stack = bufferHandler.getStackInSlot(0);
        return !stack.isEmpty() && stack.getCount() >= stack.getMaxStackSize();
    }

    public boolean isBufferEmpty() {
        return bufferHandler.getStackInSlot(0).isEmpty();
    }

    public ItemStack peekBuffer(int amount) {
        return bufferHandler.extractItem(0, amount, true);
    }

    public ItemStack extractBuffer(int amount) {
        return bufferHandler.extractItem(0, amount, false);
    }

    public ItemStack insertBuffer(ItemStack stack) {
        return bufferHandler.insertItem(0, stack, false);
    }

    public void setBufferItemStack(ItemStack stack) {
        bufferHandler.setStackInSlot(0, stack);
    }

    public boolean getEcoMode() {
        return ecoMode;
    }

    public void setHasPulsedModules(boolean hasPulsedModules) {
        this.hasPulsedModules = hasPulsedModules;
    }

    public int getRedstonePower() {
        if (redstonePower < 0) {
            redstonePower = getWorld().isBlockIndirectlyGettingPowered(pos);
        }
        return redstonePower;
    }

    public NBTTagCompound getExtData() {
        if (extData == null) {
            extData = new NBTTagCompound();
        }
        return extData;
    }

    public static TileEntityItemRouter getRouterAt(IBlockAccess world, BlockPos routerPos) {
        TileEntity te = world instanceof ChunkCache ?
                ((ChunkCache) world).getTileEntity(routerPos, Chunk.EnumCreateEntityType.CHECK) :
                world.getTileEntity(routerPos);
        return te instanceof TileEntityItemRouter ? (TileEntityItemRouter) te : null;
    }

    public void playSound(EntityPlayer player, BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
        if (getUpgradeCount(UpgradeType.MUFFLER) == 0) {
            getWorld().playSound(player, pos, sound, category, volume, pitch);
        }
    }

}
