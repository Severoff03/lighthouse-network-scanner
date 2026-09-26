package ru.lighthouse.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class DnsWire {
    static byte[] query(int id, String host, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id >>> 8); out.write(id); out.write(1); out.write(0);
        out.write(0); out.write(1); out.write(new byte[6]);
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) throw new IOException("Invalid DNS label");
            out.write(bytes.length); out.write(bytes);
        }
        out.write(0); out.write(type >>> 8); out.write(type); out.write(0); out.write(1);
        return out.toByteArray();
    }

    static String describe(byte[] data, int id) throws IOException {
        if (data.length < 12 || u16(data, 0) != (id & 65535) || (data[2] & 0x80) == 0)
            throw new IOException("Invalid DNS response");
        int questions = u16(data, 4), answers = u16(data, 6), cursor = 12;
        if (questions > 8 || answers > 128) throw new IOException("DNS response exceeds limits");
        for (int i = 0; i < questions; i++) { cursor = nameEnd(data, cursor); cursor += 4; require(data, cursor); }
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < answers; i++) {
            cursor = nameEnd(data, cursor); require(data, cursor + 10);
            int type = u16(data, cursor), length = u16(data, cursor + 8); cursor += 10;
            require(data, cursor + length);
            if ((type == 1 && length == 4) || (type == 28 && length == 16))
                addresses.add(InetAddress.getByAddress(Arrays.copyOfRange(data, cursor, cursor + length)).getHostAddress());
            cursor += length;
        }
        return "rcode=" + (data[3] & 15) + "; answers=" + answers + "; truncated=" + ((data[2] & 2) != 0)
            + "; addresses=" + String.join(", ", addresses);
    }

    private static int nameEnd(byte[] data, int cursor) throws IOException {
        for (int labels = 0; labels < 128; labels++) {
            require(data, cursor + 1); int length = data[cursor++] & 255;
            if (length == 0) return cursor;
            if ((length & 0xc0) == 0xc0) { require(data, cursor + 1); return cursor + 1; }
            if (length > 63) throw new IOException("Invalid DNS name");
            cursor += length; require(data, cursor);
        }
        throw new IOException("DNS name too long");
    }
    private static int u16(byte[] bytes, int index) { return ((bytes[index] & 255) << 8) | (bytes[index + 1] & 255); }
    private static void require(byte[] data, int end) throws IOException { if (end > data.length) throw new IOException("Truncated DNS message"); }
}
