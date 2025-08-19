package de.maxhenkel.pipez.blocks.tileentity.types;

import de.maxhenkel.pipez.*;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity.Connection;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity.FilterMode;
import de.maxhenkel.pipez.datacomponents.FluidData;
import de.maxhenkel.pipez.items.ModItems;
import de.maxhenkel.pipez.utils.ComponentUtils;
import de.maxhenkel.pipez.utils.Distributor;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class FluidPipeType extends PipeType<Fluid, FluidData> {

    public static final FluidPipeType INSTANCE = new FluidPipeType();

    @Override
    public BlockCapability<?, Direction> getCapability() {
        return Capabilities.FluidHandler.BLOCK;
    }

    @Nullable
    @Override
    public Filter<?, Fluid> createFilter() {
        return new FluidFilter();
    }

    @Override
    public String getTranslationKey() {
        return "tooltip.pipez.fluid";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.FLUID_PIPE.get());
    }

    @Override
    public Component getTransferText(@Nullable Upgrade upgrade) {
        return Component.translatable("tooltip.pipez.rate.fluid", getRate(upgrade));
    }

    @Override
    public void tick(PipeLogicTileEntity tileEntity) {
        for (Direction side : Direction.values()) {
            if (!tileEntity.isExtracting(side)) {
                continue;
            }
            if (!tileEntity.shouldWork(side, this)) {
                continue;
            }
            PipeTileEntity.Connection extractingConnection = tileEntity.getExtractingConnection(side);
            if (extractingConnection == null) {
                continue;
            }
            IFluidHandler fluidHandler = extractingConnection.getFluidHandler();
            if (fluidHandler == null) {
                continue;
            }

            List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);

            if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
                insertEqually(tileEntity, side, connections, fluidHandler);
            } else if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.FAIR)) {
                insertFair(tileEntity, side, connections, fluidHandler);
            } else {
                insertOrdered(tileEntity, side, connections, fluidHandler);
            }
        }
    }

    protected void insertFair(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IFluidHandler fluidHandler) {
        if (connections.isEmpty()) {
            return;
        }
        int mbToTransfer = getRate(tileEntity, side);

        for (int tank = 0; mbToTransfer > 0 && tank < fluidHandler.getTanks(); tank++) {
            FluidStack available = fluidHandler.drain(fluidHandler.getFluidInTank(tank).copy(), IFluidHandler.FluidAction.SIMULATE);
            if (available.isEmpty())
                continue;
            if (available.getAmount() > mbToTransfer)
                available.setAmount(mbToTransfer);

            int index = 0;
            for (int i = 0; i < connections.size(); i++) {
                Connection conn = connections.get(i);
                IFluidHandler d = conn.getFluidHandler();
                if (d != null
                        && canInsert(tileEntity, side, conn, available)
                        && (conn.resourcesNeeded = d.fill(available, FluidAction.SIMULATE)) > 0) {
                    Collections.swap(connections, i, index++);
                }
            }

            List<Connection> dests = connections.subList(0, index); // re-use of connections to avoid creating a new ArrayList
            Distributor.distributeFair(dests, available.getAmount());
            int actuallyTransfered = 0;
            for (var conn : dests) {
                IFluidHandler d = conn.getFluidHandler();
                FluidStack stack = FluidUtil.tryFluidTransfer(d, fluidHandler, available.copyWithAmount((int) conn.resourcesGiven), true);
                actuallyTransfered += stack.getAmount();
            }
            mbToTransfer -= actuallyTransfered;
        }
    }

    protected void insertEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IFluidHandler fluidHandler) {
        if (connections.isEmpty()) {
            return;
        }
        int completeAmount = getRate(tileEntity, side);
        int mbToTransfer = completeAmount;
        boolean[] connectionsFull = new boolean[connections.size()];
        int p = tileEntity.getRoundRobinIndex(side, this) % connections.size();
        while (mbToTransfer > 0 && hasNotInserted(connectionsFull)) {
            PipeTileEntity.Connection connection = connections.get(p);
            IFluidHandler destination = connection.getFluidHandler();
            boolean hasInserted = false;
            if (destination != null && !connectionsFull[p]) {
                for (int j = 0; j < fluidHandler.getTanks(); j++) {
                    FluidStack fluidInTank = fluidHandler.getFluidInTank(j);
                    FluidStack copy = fluidInTank.copy();
                    copy.setAmount(Math.min(Math.max(completeAmount / getConnectionsNotFullCount(connectionsFull), 1), mbToTransfer));
                    FluidStack simulatedExtract = fluidHandler.drain(copy, IFluidHandler.FluidAction.SIMULATE);
                    if (simulatedExtract.isEmpty()) {
                        continue;
                    }
                    if (!canInsert(tileEntity, side, connection, simulatedExtract)) {
                        continue;
                    }
                    FluidStack stack = FluidUtil.tryFluidTransfer(destination, fluidHandler, simulatedExtract, true);
                    if (stack.getAmount() > 0) {
                        mbToTransfer -= stack.getAmount();
                        hasInserted = true;
                        break;
                    }
                }
            }
            if (!hasInserted) {
                connectionsFull[p] = true;
            }
            p = (p + 1) % connections.size();
        }

        tileEntity.setRoundRobinIndex(side, this, p);
    }

    protected void insertOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IFluidHandler fluidHandler) {
        int mbToTransfer = getRate(tileEntity, side);

        connectionLoop:
        for (PipeTileEntity.Connection connection : connections) {
            IFluidHandler destination = connection.getFluidHandler();
            if (destination == null) {
                continue;
            }

            for (int i = 0; i < fluidHandler.getTanks(); i++) {
                if (mbToTransfer <= 0) {
                    break connectionLoop;
                }
                FluidStack fluidInTank = fluidHandler.getFluidInTank(i);
                FluidStack copy = fluidInTank.copy();
                copy.setAmount(mbToTransfer);
                FluidStack simulatedExtract = fluidHandler.drain(copy, IFluidHandler.FluidAction.SIMULATE);
                if (simulatedExtract.isEmpty()) {
                    continue;
                }
                if (!canInsert(tileEntity, side, connection, simulatedExtract)) {
                    continue;
                }
                FluidStack stack = FluidUtil.tryFluidTransfer(destination, fluidHandler, simulatedExtract, true);
                mbToTransfer -= stack.getAmount();
            }
        }
    }

    private boolean canInsert(PipeLogicTileEntity tileEntity, Direction side, Connection connection, FluidStack stack) {
        HolderLookup.Provider provider = tileEntity.getLevel().registryAccess();
        var filters = tileEntity.getFilters(side, this);
        boolean blacklist = FilterMode.BLACKLIST == tileEntity.getFilterMode(side, this);
        for (Filter<?, Fluid> filter : filters.stream().map(filter -> (Filter<?, Fluid>) filter).filter(f -> matchesConnection(connection, f)).collect(Collectors.toList())) {
            if (matches(provider, filter, stack)) {
                return !blacklist;
            }
        }
        return blacklist;
    }

    private boolean matches(HolderLookup.Provider provider, Filter<?, Fluid> filter, FluidStack stack) {
        CompoundTag metadata = filter.getMetadata();
        boolean tagMatches = filter.getTag() == null || filter.getTag().contains(stack.getFluid());
        if (metadata != null) {
            CompoundTag stackNBT = ComponentUtils.getTag(provider, stack);
            if (filter.isExactMetadata()) {
                return tagMatches && deepExactCompare(metadata, stackNBT);
            }
            if (stackNBT.isEmpty()) {
                return metadata.size() <= 0;
            }
            if (!deepFuzzyCompare(metadata, stackNBT)) {
                return false;
            }
        }
        return tagMatches;
    }

    private boolean hasNotInserted(boolean[] inventoriesFull) {
        for (boolean b : inventoriesFull) {
            if (!b) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getRate(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.fluidPipeAmount.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.fluidPipeAmountBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.fluidPipeAmountImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.fluidPipeAmountAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.fluidPipeAmountUltimate.get();
            case INFINITY:
            default:
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public DataComponentType<FluidData> getDataComponentType() {
        return ModItems.FLUID_DATA_COMPONENT.get();
    }

    private static final FluidData DEFAULT = new FluidData(UpgradeTileEntity.FilterMode.BLACKLIST, UpgradeTileEntity.RedstoneMode.IGNORED, UpgradeTileEntity.Distribution.FAIR, Collections.emptyList());

    @Override
    public FluidData defaultData() {
        return DEFAULT;
    }

}
