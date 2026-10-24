// Tries the drive code of the app on image files, on a PC:
//   tools/drive-test/run.sh
// Formats through a fake USB stick (FakeTransport, a Bulk-Only drive kept in
// a file, ported from the tests of usbformat, MIT) and on image files, and
// inspects and fixes images made by mkfs.exfat, mkfs.fat and sfdisk. run.sh
// then checks the results with fsck.fat, sfdisk and mtools.
import org.morpheas.rbdoctor.drive.Disk;
import org.morpheas.rbdoctor.drive.DriveInfo;
import org.morpheas.rbdoctor.drive.DriveLog;
import org.morpheas.rbdoctor.drive.DriveTools;
import org.morpheas.rbdoctor.drive.ScsiDisk;
import org.morpheas.rbdoctor.drive.UsbTransport;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public class DriveTest {
    static class FileDisk implements Disk {
        final RandomAccessFile raf;
        final int sectorSize;

        FileDisk(String path, int sectorSize) throws IOException {
            raf = new RandomAccessFile(path, "rw");
            this.sectorSize = sectorSize;
        }

        public int sectorSize() { return sectorSize; }

        public long sectorCount() {
            try {
                return raf.length() / sectorSize;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public byte[] read(long lba, int count) throws IOException {
            byte[] buf = new byte[count * sectorSize];
            raf.seek(lba * sectorSize);
            raf.readFully(buf);
            return buf;
        }

        public void write(long lba, byte[] data, int length) throws IOException {
            raf.seek(lba * sectorSize);
            raf.write(data, 0, length);
        }
    }

    /** A USB Bulk-Only drive kept in a file: parses command blocks and answers like a real one. */
    static class FakeTransport implements UsbTransport {
        final RandomAccessFile storage;
        final long sectors;
        final int blockSize = 512;
        boolean stallNextWrite;
        int commands;
        byte[] reply = new byte[0];
        int replyPos;
        long tag;
        long writeAt;
        int writeLeft;

        FakeTransport(String path) throws IOException {
            storage = new RandomAccessFile(path, "rw");
            sectors = storage.length() / blockSize;
        }

        void queue(byte[] data) {
            byte[] rest = Arrays.copyOfRange(reply, replyPos, reply.length);
            reply = Arrays.copyOf(rest, rest.length + data.length);
            System.arraycopy(data, 0, reply, rest.length, data.length);
            replyPos = 0;
        }

        void queueStatus(int status) {
            byte[] csw = new byte[13];
            le(csw, 0, 0x53425355L, 4);
            le(csw, 4, tag, 4);
            csw[12] = (byte) status;
            queue(csw);
        }

        public int bulkOut(byte[] data, int offset, int length, int timeoutMs) {
            try {
                if (writeLeft > 0 && stallNextWrite) {
                    // The drive refuses the data phase and reports it in the status block
                    stallNextWrite = false;
                    writeLeft = 0;
                    queueStatus(1);
                    return -1;
                }
                if (writeLeft > 0) {
                    storage.seek(writeAt);
                    storage.write(data, offset, length);
                    writeAt += length;
                    writeLeft -= length;
                    if (writeLeft == 0) queueStatus(0);
                    return length;
                }
                if (length != 31 || readLe(data, offset, 4) != 0x43425355L) throw new IllegalStateException("bad command block");
                commands++;
                tag = readLe(data, offset + 4, 4);
                int transferLength = (int) readLe(data, offset + 8, 4);
                byte[] cdb = Arrays.copyOfRange(data, offset + 15, offset + 15 + (data[offset + 14] & 0xFF));
                int op = cdb[0] & 0xFF;
                switch (op) {
                    case 0x00: case 0x35: queueStatus(0); break;
                    case 0x25: {
                        byte[] out = new byte[8];
                        be(out, 0, sectors - 1, 4);
                        be(out, 4, blockSize, 4);
                        queue(out);
                        queueStatus(0);
                        break;
                    }
                    case 0x28: read(readBe(cdb, 2, 4), (int) readBe(cdb, 7, 2), transferLength); break;
                    case 0x2A: startWrite(readBe(cdb, 2, 4), (int) readBe(cdb, 7, 2), transferLength); break;
                    case 0x12: {
                        byte[] out = new byte[36];
                        out[1] = (byte) 0x80;
                        System.arraycopy("FAKE    Fake stick      1.0 ".getBytes(), 0, out, 8, 28);
                        queue(out);
                        queueStatus(0);
                        break;
                    }
                    case 0x1A: queue(new byte[4]); queueStatus(0); break;
                    case 0x03: queue(new byte[18]); queueStatus(0); break;
                    default: queueStatus(1);
                }
                return length;
            } catch (IOException e) {
                return -1;
            }
        }

        void read(long lba, int count, int transferLength) throws IOException {
            if (transferLength != count * blockSize) throw new IllegalStateException("length mismatch");
            byte[] out = new byte[transferLength];
            storage.seek(lba * blockSize);
            storage.readFully(out);
            queue(out);
            queueStatus(0);
        }

        void startWrite(long lba, int count, int transferLength) {
            if (transferLength != count * blockSize) throw new IllegalStateException("length mismatch");
            writeAt = lba * blockSize;
            writeLeft = transferLength;
        }

        public int bulkIn(byte[] buffer, int offset, int length, int timeoutMs) {
            int available = reply.length - replyPos;
            if (available == 0) return -1;
            int n = Math.min(available, length);
            System.arraycopy(reply, replyPos, buffer, offset, n);
            replyPos += n;
            return n;
        }

        public void clearHalt(boolean in) {}

        public void reset() { reply = new byte[0]; replyPos = 0; writeLeft = 0; }

        public void close() {}

        static void le(byte[] b, int o, long v, int n) { for (int i = 0; i < n; i++) b[o + i] = (byte) (v >> (8 * i)); }
        static void be(byte[] b, int o, long v, int n) { for (int i = 0; i < n; i++) b[o + i] = (byte) (v >> (8 * (n - 1 - i))); }
        static long readLe(byte[] b, int o, int n) { long v = 0; for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFF); return v; }
        static long readBe(byte[] b, int o, int n) { long v = 0; for (int i = 0; i < n; i++) v = (v << 8) | (b[o + i] & 0xFF); return v; }
    }

    static void print(String name, DriveInfo info) {
        System.out.print(name + ": table=" + info.table);
        for (DriveInfo.Partition p : info.partitions) {
            System.out.printf(" [%d type=%s fs=%s label='%s' start=%d sectors=%d]", p.slot,
                    p.type < 0 ? "-" : String.format("0x%02x", p.type), p.fs, p.label, p.start, p.sectors);
        }
        System.out.println();
        for (String problem : info.playerProblems()) System.out.println("  problem: " + problem);
    }

    public static void main(String[] args) throws Exception {
        String command = args[0];
        String image = args[1];
        switch (command) {
            case "inspect":
                print(image, DriveInfo.inspect(new FileDisk(image, 512)));
                break;
            case "fix": {
                FileDisk disk = new FileDisk(image, 512);
                int type = DriveTools.fixPartitionType(disk, Integer.parseInt(args[2]));
                System.out.printf("fixed to 0x%02x%n", type);
                print(image, DriveInfo.inspect(disk));
                break;
            }
            case "format":
                DriveTools.formatFat32(new FileDisk(image, 512), args[2]);
                print(image, DriveInfo.inspect(new FileDisk(image, 512)));
                break;
            case "usb-format": {
                // Through the SCSI layer, with a stalled write on the way
                FakeTransport transport = new FakeTransport(image);
                ScsiDisk disk = new ScsiDisk(transport, 30_000);
                transport.stallNextWrite = true;
                DriveTools.formatFat32(disk, args[2]);
                print(image + " (" + disk.product + ", " + transport.commands + " commands)", DriveInfo.inspect(disk));
                break;
            }
            default:
                throw new IllegalArgumentException(command);
        }
        if (System.getenv("DRIVE_LOG") != null) System.out.println(DriveLog.text());
    }
}
