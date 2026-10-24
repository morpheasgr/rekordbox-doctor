// Ported from usbformat (https://github.com/blackorange-sketch/usbformat),
// MIT licensed, see third_party/usbformat/LICENSE.
package org.morpheas.rbdoctor.drive;

import java.io.IOException;

/** A drive addressed by sectors. Plain Java, so it can be tried on a PC with an image file. */
public interface Disk {
    int MIB = 1024 * 1024;

    int sectorSize();

    long sectorCount();

    /** Reads count sectors from lba. */
    byte[] read(long lba, int count) throws IOException;

    /** Writes the first length bytes of data at lba; length is a multiple of the sector size. */
    void write(long lba, byte[] data, int length) throws IOException;

    default void write(long lba, byte[] data) throws IOException {
        write(lba, data, data.length);
    }

    default void flush() throws IOException {
    }

    /** Overwrites sectors sectors from lba with zeros. */
    default void zero(long lba, long sectors) throws IOException {
        int chunk = Math.max(1, MIB / sectorSize());
        byte[] zeros = new byte[chunk * sectorSize()];
        for (long done = 0; done < sectors; ) {
            int n = (int) Math.min(chunk, sectors - done);
            write(lba + done, zeros, n * sectorSize());
            done += n;
        }
    }
}
