package heapo.unpack;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps a DataInputStream and tracks the number of bytes consumed.
 * Exposes the same typed read methods as DataInputStream.
 */
final class CountingInputStream {

    private final DataInputStream in;
    private long position = 0;

    CountingInputStream(InputStream in) {
        this.in = new DataInputStream(in);
    }

    long position() {
        return position;
    }

    int readUnsignedByte() throws IOException {
        int b = in.readUnsignedByte();
        position++;
        return b;
    }

    int readUnsignedShort() throws IOException {
        int v = in.readUnsignedShort();
        position += 2;
        return v;
    }

    int readInt() throws IOException {
        int v = in.readInt();
        position += 4;
        return v;
    }

    long readLong() throws IOException {
        long v = in.readLong();
        position += 8;
        return v;
    }

    byte[] readNBytes(long n) throws IOException {
        if (n > Integer.MAX_VALUE) {
            throw new IOException("readNBytes: requested " + n + " bytes, exceeds int range");
        }
        byte[] bytes = in.readNBytes((int) n);
        position += bytes.length;
        return bytes;
    }

    void skipNBytes(long n) throws IOException {
        in.skipNBytes(n);
        position += n;
    }
}
