package io.github.fdrn9999.marketplace.common;

import java.util.UUID;

/** 접두어가 붙은 짧은 ID 생성기 (예: {@code evt-3f9a1c2b7d4e}). */
public final class Ids {

    private Ids() {
    }

    public static String next(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
