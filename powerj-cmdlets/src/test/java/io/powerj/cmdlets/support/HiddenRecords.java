package io.powerj.cmdlets.support;

import io.powerj.api.Bytes;

/** Records that code outside this package cannot see, as those of other modules can be: only reflection that made the accessors accessible reads them. */
public final class HiddenRecords {

    private record Secret(String value, @Bytes long size) {
    }

    private HiddenRecords() {
    }

    public static Object secret() {
        return new Secret("s", 5);
    }
}
