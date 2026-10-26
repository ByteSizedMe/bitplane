package com.sathwikhbhat.bitplane.parallel;

import java.util.Locale;

public enum ParallelMode {
    SEQUENTIAL,
    THREADS,
    GPU,
    MPI;

    public static ParallelMode parse(String value) {
        if (value == null || value.isBlank()) {
            return SEQUENTIAL;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
