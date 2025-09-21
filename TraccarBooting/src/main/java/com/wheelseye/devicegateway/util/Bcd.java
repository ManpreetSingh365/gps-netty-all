package com.wheelseye.devicegateway.util;

public final class Bcd {
    private Bcd() {}

    public static String bcdToString(byte[] bcd) {
        StringBuilder sb = new StringBuilder(bcd.length * 2);
        for (byte b : bcd) {
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            sb.append(hi);
            sb.append(lo);
        }
        // remove leading zeros (GT06 login often contains leading 0)
        String s = sb.toString();
        return s.replaceFirst("^0+", "");
    }
}
