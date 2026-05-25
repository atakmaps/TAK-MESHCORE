package com.atakmaps.meshcore.plugin.aprs;

/**
 * Common APRS symbols for the outbound icon picker (Primary / and Alternate \ tables).
 */
public final class AprsSymbolCatalog {

    public static final class Entry {
        public final char table;
        public final char code;
        public final String label;

        public Entry(char table, char code, String label) {
            this.table = table;
            this.code = code;
            this.label = label;
        }
    }

    public static final Entry[] PICKER_ENTRIES = {
            new Entry('/', '>', "Car / mobile"),
            new Entry('/', '-', "House / QTH"),
            new Entry('/', 'k', "Truck"),
            new Entry('/', 'b', "Bike"),
            new Entry('/', 'Y', "Ship / boat"),
            new Entry('/', 'a', "Ambulance"),
            new Entry('/', 'O', "Balloon"),
            new Entry('/', 'j', "Jeep"),
            new Entry('/', 'u', "Bus"),
            new Entry('/', '[', "Mail"),
            new Entry('/', 'h', "Hospital"),
            new Entry('/', 'r', "Repeater"),
            new Entry('/', 'I', "TCP/IP"),
            new Entry('/', '_', "Weather station"),
            new Entry('/', 'W', "NWS site"),
            new Entry('\\', '>', "Overlay / car"),
            new Entry('\\', 'k', "Overlay / truck"),
            new Entry('\\', 'b', "Overlay / bike"),
            new Entry('\\', 'j', "Overlay / jeep"),
    };

    private AprsSymbolCatalog() {
    }

    public static String labelFor(char table, char code) {
        for (Entry e : PICKER_ENTRIES) {
            if (e.table == table && e.code == code) {
                return e.label;
            }
        }
        return "Symbol " + table + "/" + code;
    }
}
