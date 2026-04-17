package com.wenting.mediaserver.protocol.rtp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Reusable RTP/RTCP UDP port-pair pool (even RTP port + odd RTCP port).
 */
public final class UdpPortPairPool {

    private final int portRangeMin;
    private final int portRangeMax;
    private final Deque<Integer> availableEvenPorts = new ArrayDeque<Integer>();
    private final Set<Integer> leasedEvenPorts = new HashSet<Integer>();

    public UdpPortPairPool(int portRangeMin, int portRangeMax) {
        int min = clampPort(portRangeMin);
        int max = clampPort(portRangeMax);
        this.portRangeMin = Math.min(min, max);
        this.portRangeMax = Math.max(min, max);
        int evenStart = (this.portRangeMin & 1) == 0 ? this.portRangeMin : this.portRangeMin + 1;
        if (evenStart + 1 > this.portRangeMax) {
            throw new IllegalArgumentException(
                    "RTP UDP port range too small for RTP/RTCP pair: " + this.portRangeMin + "-" + this.portRangeMax);
        }
        for (int p = evenStart; p + 1 <= this.portRangeMax; p += 2) {
            availableEvenPorts.addLast(Integer.valueOf(p));
        }
    }

    public synchronized Integer acquireEvenPort() {
        int attempts = availableEvenPorts.size();
        for (int i = 0; i < attempts; i++) {
            Integer pObj = availableEvenPorts.pollFirst();
            if (pObj == null) {
                break;
            }
            if (!leasedEvenPorts.contains(pObj)) {
                leasedEvenPorts.add(pObj);
                availableEvenPorts.addLast(pObj);
                return pObj;
            }
            availableEvenPorts.addLast(pObj);
        }
        return null;
    }

    public synchronized void releaseEvenPort(int evenPort) {
        leasedEvenPorts.remove(Integer.valueOf(evenPort));
    }

    public int portRangeMin() {
        return portRangeMin;
    }

    public int portRangeMax() {
        return portRangeMax;
    }

    public synchronized int pairCapacity() {
        return availableEvenPorts.size();
    }

    private static int clampPort(int port) {
        if (port <= 0) {
            return 1;
        }
        if (port > 65535) {
            return 65535;
        }
        return port;
    }
}
