/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.system;

import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SystemMonitor {

    private static final double GB = 1024.0 * 1024 * 1024;

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        var os = ManagementFactory.getOperatingSystemMXBean();
        m.put("os", os.getName() + " " + os.getVersion());
        m.put("cores", os.getAvailableProcessors());

        if (os instanceof com.sun.management.OperatingSystemMXBean s) {
            double cpu = s.getCpuLoad();
            m.put("cpu", cpu < 0 ? 0 : Math.round(cpu * 100));
            long total = s.getTotalMemorySize();
            long free = s.getFreeMemorySize();
            m.put("ram", total > 0 ? Math.round((total - free) * 100.0 / total) : 0);
            m.put("ramTotalGb", Math.round(total / GB * 10) / 10.0);
        }

        File root = File.listRoots()[0];
        long dTotal = root.getTotalSpace();
        long dFree = root.getUsableSpace();
        m.put("disk", dTotal > 0 ? Math.round((dTotal - dFree) * 100.0 / dTotal) : 0);
        m.put("diskFreeGb", Math.round(dFree / GB));

        m.put("uptimeMin", ManagementFactory.getRuntimeMXBean().getUptime() / 60000);
        m.put("host", hostName());
        m.put("ip", lanIp());
        return m;
    }

    private static String hostName() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "desconocido"; }
    }

    private static String lanIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                if (name.contains("virtual") || name.contains("vethernet") || name.contains("vmware")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("192.168.")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) { }
        return fallback != null ? fallback : "127.0.0.1";
    }
}
