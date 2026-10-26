package com.sathwikhbhat.bitplane.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Collects hardware / software facts by asking the machine; reports "not available" when a tool is missing. */
final class Hardware {

    private Hardware() {}

    static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU model        : ")
                .append(grep("/proc/cpuinfo", "model name"))
                .append('\n');
        sb.append("Logical CPUs     : ")
                .append(Runtime.getRuntime().availableProcessors())
                .append(" (Runtime.availableProcessors)\n");
        sb.append("lscpu topology   : ")
                .append(
                        run(
                                "sh",
                                "-c",
                                "lscpu | grep -E '^(Socket|Core|Thread|Hypervisor|Virtualization type)' | tr -s ' ' | paste -sd ';'"))
                .append('\n');
        sb.append("RAM              : ")
                .append(grep("/proc/meminfo", "MemTotal"))
                .append('\n');
        sb.append("GPU (nvidia-smi) : ")
                .append(run("nvidia-smi", "--query-gpu=name,driver_version", "--format=csv,noheader"))
                .append('\n');
        sb.append("OpenCL devices   : ")
                .append(
                        run(
                                "sh",
                                "-c",
                                "clinfo 2>/dev/null | grep -E 'Device Name|Device Type|Driver Version' | tr -s ' ' | paste -sd ';' | cut -c1-400"))
                .append('\n');
        sb.append("OS               : ")
                .append(grep("/etc/os-release", "PRETTY_NAME"))
                .append(" / kernel ")
                .append(run("uname", "-r"))
                .append('\n');
        sb.append("Java             : ")
                .append(System.getProperty("java.version"))
                .append(" (")
                .append(System.getProperty("java.vm.name"))
                .append(", ")
                .append(System.getProperty("java.vendor"))
                .append(")\n");
        sb.append("FFmpeg           : ")
                .append(firstLine(run("ffmpeg", "-version")))
                .append('\n');
        sb.append("MPI              : ")
                .append(firstLine(run("mpirun", "--version")))
                .append(" ; Java bindings built from Open MPI source of the same version\n");
        sb.append("JVM max heap     : ")
                .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                .append(" MiB\n");
        return sb.toString().stripTrailing();
    }

    private static String grep(String file, String key) {
        try {
            List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith(key)) {
                    return line.substring(
                                    line.indexOf(':') >= 0 && !file.contains("os-release")
                                            ? line.indexOf(':') + 1
                                            : line.indexOf('=') + 1)
                            .replace("\"", "")
                            .trim();
                }
            }
        } catch (IOException e) {
            return "not available (" + e.getMessage() + ")";
        }
        return "not available";
    }

    private static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return out.isEmpty()
                        ? "not available (exit " + p.exitValue() + ")"
                        : "not available (" + firstLine(out) + ")";
            }
            return out.isEmpty() ? "none" : out;
        } catch (IOException | InterruptedException e) {
            return "not available (" + command[0] + " not found)";
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
