package de.maxhenkel.pipez.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity.Connection;

public class Distributor implements Iterable<Distributor.ConnectionResource> {

    private final List<ConnectionResource> dests;

    public static class ConnectionResource {
        public Connection conn;  // serves as an ID to identify the handler
        public long value;       // resource needed or allocated

        public ConnectionResource(Connection conn, long needed) {
            this.conn = conn;
            this.value = needed;
        }
    }

    public Distributor(int size) {
        dests = new ArrayList<ConnectionResource>(size);
    }

    public void add(Connection conn, long needed) {
        dests.add(new ConnectionResource(conn, needed));
    }

    @Override
    public Iterator<ConnectionResource> iterator() {
        return dests.iterator();
    }

    /**
     * Fair distribution of <code>amount</code> among multiple destinations.
     * Each destination receives the same amount of resources. If that fills
     * the destination, the rest is shared equally with the others.
     * @param amount The maximum amount of resources to distribute
     * @return What's left undistributed (0 if everything was distributed).
     */
    public long distributeFair(long amount) {
        if (dests.isEmpty() || amount <= 0)
            return amount;

        dests.sort((a, b) -> Long.compare(b.value, a.value));
        int n = dests.size();
        while (n --> 0) {
            dests.get(n).value = Math.min(amount / (n+1), dests.get(n).value);
            amount -= dests.get(n).value;
        }
        return amount;
    }
}
