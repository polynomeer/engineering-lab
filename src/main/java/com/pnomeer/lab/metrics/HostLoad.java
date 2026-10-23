package com.pnomeer.lab.metrics;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Host-wide load around a run, so unstable repetitions can be explained afterwards. The 1-minute load
 * average lags and includes this lab's own work; values the platform cannot report are omitted.
 */
public final class HostLoad {
    private HostLoad() { }

    /** {@code host.load1m.<phase>} and, at the end, {@code host.cpuPct.end}. */
    public static Map<String, Double> sample(String phase) {
        var values = new HashMap<String, Double>();
        var os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        if (load >= 0) values.put("host.load1m." + phase, load);
        if (phase.equals("end") && os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double cpu = sun.getCpuLoad();
            if (cpu >= 0) values.put("host.cpuPct.end", cpu * 100);
        }
        return values;
    }
}
