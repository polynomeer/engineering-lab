package com.pnomeer.lab.core;

/** Allowlisted runtime facts, not a dump of potentially secret JVM arguments or properties. */
public record RunEnvironment(
        String javaVersion,
        String javaVendor,
        String vmName,
        String osName,
        String osVersion,
        String osArch,
        int availableProcessors,
        long maxHeapBytes) {

    public static RunEnvironment capture() {
        Runtime runtime = Runtime.getRuntime();
        return new RunEnvironment(
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                runtime.availableProcessors(),
                runtime.maxMemory());
    }
}
