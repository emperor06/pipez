package de.maxhenkel.pipez.utils;

import java.util.List;

import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity.Connection;

public class Distributor {

    /**
     * Fair distribution of <code>amount</code> among multiple destinations.
     * Each destination receives the same amount of resources. If that fills
     * the destination, the rest is shared equally with the others.
     * @param amount The maximum amount of resources to distribute
     * @return What's left undistributed (0 if everything was distributed).
     */
    public static long distributeFair(List<Connection> dests, long amount) {
        if (dests.isEmpty())
            return amount;
        if (amount <= 0) {
            dests.forEach(x -> x.resourcesGiven = 0);
            return amount;
        }

        dests.sort((a, b) -> Long.compare(b.resourcesNeeded, a.resourcesNeeded));
        int n = dests.size();
        while (n --> 0) {
            dests.get(n).resourcesGiven = Math.min(amount / (n+1), dests.get(n).resourcesNeeded);
            amount -= dests.get(n).resourcesGiven;
        }
        return amount;
    }
}
