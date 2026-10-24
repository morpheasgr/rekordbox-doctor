// Ported from usbformat (https://github.com/blackorange-sketch/usbformat),
// MIT licensed, see third_party/usbformat/LICENSE.
package org.morpheas.rbdoctor.drive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A USB flash drive as a Disk: SCSI commands (READ/WRITE, READ CAPACITY,
 * SYNCHRONIZE CACHE) wrapped in Bulk-Only Transport command and status
 * blocks. Plain Java, so it can be tried with a fake transport. Everything
 * unusual goes to DriveLog.
 */
public class ScsiDisk implements Disk, AutoCloseable {
    private static final long CBW_SIGNATURE = 0x43425355L; // "USBC"
    private static final long CSW_SIGNATURE = 0x53425355L; // "USBS"
    private static final int COMMAND_TIMEOUT_MS = 5_000; // a 31-byte command block should be instant
    private static final int INIT_TIMEOUT_MS = 5_000; // while waiting for the drive to come up
    private static final int CHUNK = 16 * 1024; // per bulk transfer; a multiple of every bulk packet size
    private static final int RAMP_START = 512;
    private static final int MAX_COMMAND_BYTES = 64 * 1024; // per SCSI command; cheap controllers dislike more
    private static final int LOGGED_READ_WRITES = 8;

    /** The drive stopped answering even after every kind of reset; it has to be replugged. */
    public static class DriveUnresponsiveException extends IOException {
        DriveUnresponsiveException(String message) {
            super(message);
        }
    }

    private static class Result {
        final int status;
        final int transferred;

        Result(int status, int transferred) {
            this.status = status;
            this.transferred = transferred;
        }
    }

    private static class Sense {
        final int key;
        final int asc;
        final int ascq;

        Sense(int key, int asc, int ascq) {
            this.key = key;
            this.asc = asc;
            this.ascq = ascq;
        }
    }

    /**
     * Command size that starts tiny and doubles after every success, up to
     * max. After a failure it goes back to the last size that worked and
     * stops growing, so a drive or phone that can't take big transfers
     * still works.
     */
    private static class Ramp {
        private final int min;
        private final int max;
        int bytes;
        private boolean limited;

        Ramp(int min, int max) {
            this.min = min;
            this.max = max;
            this.bytes = min;
        }

        void success() {
            if (!limited && bytes < max) {
                bytes = Math.min(max, bytes * 2);
            }
        }

        /** False when the size was already at the minimum. */
        boolean failure() {
            limited = true;
            if (bytes <= min) {
                return false;
            }
            bytes = Math.max(min, bytes / 2);
            return true;
        }
    }

    private final UsbTransport transport;
    public volatile int ioTimeoutMs;
    private long tag = 1;
    private int readWritesLogged;
    private final Ramp readRamp = new Ramp(RAMP_START, MAX_COMMAND_BYTES);
    private final Ramp writeRamp = new Ramp(RAMP_START, MAX_COMMAND_BYTES);
    private boolean initializing = true;
    private final int sectorSize;
    private final long sectorCount;
    /** From INQUIRY, e.g. "SanDisk Ultra"; empty when unknown */
    public String product = "";
    public boolean writeProtected;

    public ScsiDisk(UsbTransport transport, int ioTimeoutMs) throws IOException {
        this.transport = transport;
        this.ioTimeoutMs = ioTimeoutMs;
        log("opening, I/O timeout " + ioTimeoutMs + " ms");
        waitUntilReady();
        long[] capacity = readCapacity();
        int blockSize = (int) capacity[0];
        if (blockSize != 512 && blockSize != 4096) {
            throw new IOException("Unsupported sector size: " + blockSize + " bytes");
        }
        sectorSize = blockSize;
        sectorCount = capacity[1];
        log("capacity: " + sectorCount + " sectors of " + sectorSize + " bytes");
        readDeviceInfo();
        initializing = false;
    }

    @Override
    public int sectorSize() {
        return sectorSize;
    }

    @Override
    public long sectorCount() {
        return sectorCount;
    }

    @Override
    public byte[] read(long lba, int count) throws IOException {
        byte[] out = new byte[count * sectorSize];
        transferAll(false, lba, out, count);
        return out;
    }

    @Override
    public void write(long lba, byte[] data, int length) throws IOException {
        if (length % sectorSize != 0) {
            throw new IllegalArgumentException("Length must be a multiple of the sector size");
        }
        transferAll(true, lba, data, length / sectorSize);
    }

    /** Asks the drive to commit its write cache. Not every drive supports it, so a failure is no error. */
    @Override
    public void flush() {
        byte[] cdb = new byte[10];
        cdb[0] = 0x35; // SYNCHRONIZE CACHE(10)
        try {
            Result r = execute(cdb, true, null, 0, 0, CHUNK);
            if (r.status != 0) {
                requestSense();
            }
        } catch (IOException e) {
            log("flush failed (ignored): " + e.getMessage());
        }
    }

    @Override
    public void close() {
        log("closing");
        transport.close();
    }

    private static void log(String message) {
        DriveLog.log("scsi: " + message);
    }

    private int timeoutMs() {
        return initializing ? INIT_TIMEOUT_MS : ioTimeoutMs;
    }

    /** Keeps the link awake and checks the drive still answers. */
    public boolean ping() {
        try {
            return execute(new byte[6], true, null, 0, 0, CHUNK).status == 0; // TEST UNIT READY
        } catch (IOException e) {
            return false;
        }
    }

    private void transferAll(boolean write, long lba, byte[] buf, int count) throws IOException {
        String what = write ? "write" : "read";
        Ramp ramp = write ? writeRamp : readRamp;
        int done = 0;
        int errorsAtMinimum = 0;
        while (done < count) {
            int n = Math.min(count - done, Math.max(1, ramp.bytes / sectorSize));
            try {
                transferSectors(write, lba + done, n, buf, done * sectorSize);
            } catch (IOException e) {
                log(what + " of " + (n * sectorSize) + " bytes at sector " + (lba + done) + " failed: " + e.getMessage());
                if (e instanceof DriveUnresponsiveException) {
                    throw e;
                }
                if (ramp.failure()) {
                    log(what + ": using " + ramp.bytes + " bytes per command from now on");
                } else if (++errorsAtMinimum > 3) {
                    throw e;
                }
                sleep(300);
                continue;
            }
            ramp.success();
            done += n;
        }
    }

    /** What the drive says about itself, and whether it reports write protection. */
    private void readDeviceInfo() {
        try {
            byte[] inquiry = new byte[36];
            byte[] cdb = new byte[6];
            cdb[0] = 0x12; // INQUIRY
            cdb[4] = 36;
            Result r = execute(cdb, true, inquiry, 0, 36, CHUNK);
            if (r.status == 0 && r.transferred >= 32) {
                String vendor = ascii(inquiry, 8, 8);
                String model = ascii(inquiry, 16, 16);
                product = (vendor + " " + model).trim();
                log("inquiry: type=" + (inquiry[0] & 0x1F) + " removable=" + ((inquiry[1] & 0x80) != 0)
                        + " vendor='" + vendor + "' product='" + model + "' revision='" + ascii(inquiry, 32, 4) + "'");
            } else {
                requestSense();
            }
        } catch (IOException e) {
            log("inquiry failed: " + e.getMessage());
        }
        try {
            byte[] header = new byte[4];
            byte[] cdb = new byte[6];
            cdb[0] = 0x1A; // MODE SENSE(6)
            cdb[2] = 0x3F;
            cdb[4] = 4;
            Result r = execute(cdb, true, header, 0, 4, CHUNK);
            if (r.status == 0 && r.transferred >= 4) {
                writeProtected = (header[2] & 0x80) != 0;
                log("mode sense: write protected=" + writeProtected);
            } else {
                requestSense();
            }
        } catch (IOException e) {
            log("mode sense failed: " + e.getMessage());
        }
    }

    private static String ascii(byte[] b, int offset, int length) {
        return new String(b, offset, length, StandardCharsets.US_ASCII).trim();
    }

    private void transferSectors(boolean write, long lba, int sectors, byte[] buf, int offset) throws IOException {
        byte[] cdb;
        if (lba + sectors <= 0xFFFFFFFFL) {
            cdb = new byte[10];
            cdb[0] = (byte) (write ? 0x2A : 0x28); // WRITE(10) / READ(10)
            putBe(cdb, 2, lba, 4);
            putBe(cdb, 7, sectors, 2);
        } else {
            cdb = new byte[16];
            cdb[0] = (byte) (write ? 0x8A : 0x88); // WRITE(16) / READ(16)
            putBe(cdb, 2, lba, 8);
            putBe(cdb, 10, sectors, 4);
        }
        String what = write ? "write" : "read";
        int bytes = sectors * sectorSize;
        for (int attempt = 0; attempt < 2; attempt++) {
            Result r = execute(cdb, !write, buf, offset, bytes, Math.min(CHUNK, bytes));
            if (r.status == 0 && r.transferred == bytes) {
                return;
            }
            Sense sense = requestSense();
            log(String.format(Locale.US, "%s at sector %d: status=%d moved=%d/%d sense key=0x%02x asc=0x%02x ascq=0x%02x",
                    what, lba, r.status, r.transferred, bytes, sense.key, sense.asc, sense.ascq));
            // A "unit attention" (media changed, bus reset) is reported once; the retry then succeeds
            if (sense.key == 6 && attempt == 0) {
                continue;
            }
            if (write && sense.key == 7) {
                throw new IOException("The stick is write-protected (a lock switch on it, or it has worn out)");
            }
            throw new IOException(String.format(Locale.US, "SCSI %s failed at sector %d (sense key 0x%02x, code 0x%02x/0x%02x)",
                    what, lba, sense.key, sense.asc, sense.ascq));
        }
    }

    private void waitUntilReady() throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Result r = execute(new byte[6], true, null, 0, 0, CHUNK); // TEST UNIT READY
                if (r.status == 0) {
                    return;
                }
                requestSense();
            } catch (IOException e) {
                // Right after the drive was taken over from Android it can
                // need a moment (and a reset) to answer
                log("not ready yet (attempt " + (attempt + 1) + "): " + e.getMessage());
                if (attempt >= 1) {
                    throw e;
                }
            }
            if (attempt < 9) {
                sleep(300);
            }
        }
        log("the drive never reported ready; continuing anyway");
    }

    /** {block size, block count} */
    private long[] readCapacity() throws IOException {
        byte[] small = new byte[8];
        byte[] cdb10 = new byte[10];
        cdb10[0] = 0x25; // READ CAPACITY(10)
        Result r = execute(cdb10, true, small, 0, 8, CHUNK);
        if (r.status != 0 || r.transferred != 8) {
            requestSense();
            throw new IOException("The drive did not report its capacity");
        }
        long last = getBe(small, 0, 4);
        long blockSize = getBe(small, 4, 4);
        if (last != 0xFFFFFFFFL) {
            return new long[] {blockSize, last + 1};
        }
        // Larger than 2 TiB: the 16-byte variant carries a 64-bit address
        byte[] big = new byte[32];
        byte[] cdb16 = new byte[16];
        cdb16[0] = (byte) 0x9E; // SERVICE ACTION IN(16)
        cdb16[1] = 0x10; // READ CAPACITY(16)
        putBe(cdb16, 10, 32, 4);
        r = execute(cdb16, true, big, 0, 32, CHUNK);
        if (r.status != 0 || r.transferred != 32) {
            requestSense();
            throw new IOException("The drive did not report its capacity");
        }
        return new long[] {getBe(big, 8, 4), getBe(big, 0, 8) + 1};
    }

    private Sense requestSense() {
        byte[] buf = new byte[18];
        byte[] cdb = new byte[6];
        cdb[0] = 0x03;
        cdb[4] = 18;
        try {
            Result r = execute(cdb, true, buf, 0, 18, CHUNK);
            if (r.status == 0 && r.transferred >= 14) {
                return new Sense(buf[2] & 0x0F, buf[12] & 0xFF, buf[13] & 0xFF);
            }
        } catch (IOException ignored) {
        }
        return new Sense(0, 0, 0);
    }

    private int send(byte[] cbw) {
        return transport.bulkOut(cbw, 0, cbw.length, COMMAND_TIMEOUT_MS);
    }

    /** One command block, the data phase if any, then the status block. */
    private synchronized Result execute(byte[] cdb, boolean dataIn, byte[] data, int offset, int length, int maxTransfer)
            throws IOException {
        int op = cdb[0] & 0xFF;
        long myTag = tag;
        tag = (tag + 1) & 0xFFFFFFFFL;

        byte[] cbw = new byte[31];
        putLe(cbw, 0, CBW_SIGNATURE, 4);
        putLe(cbw, 4, myTag, 4);
        putLe(cbw, 8, length, 4);
        cbw[12] = (byte) (dataIn && length > 0 ? 0x80 : 0);
        cbw[14] = (byte) cdb.length;
        System.arraycopy(cdb, 0, cbw, 15, cdb.length);

        // Escalating recovery when the drive doesn't take the command block
        int sent = send(cbw);
        if (sent != cbw.length) {
            log(opName(op) + ": command block not accepted (result " + sent + "); clearing halt and retrying");
            transport.clearHalt(false);
            sent = send(cbw);
        }
        if (sent != cbw.length) {
            log(opName(op) + ": still not accepted (result " + sent + "); resynchronising both endpoints");
            transport.resync();
            sent = send(cbw);
        }
        if (sent != cbw.length) {
            log(opName(op) + ": still not accepted (result " + sent + "); Bulk-Only reset");
            transport.reset();
            transport.resync();
            sent = send(cbw);
        }
        if (sent != cbw.length) {
            log(opName(op) + ": still not accepted (result " + sent + "); resetting the whole device");
            if (transport.hardReset()) {
                sleep(1500);
                sent = send(cbw);
            } else {
                log("a device reset is not available");
            }
        }
        if (sent != cbw.length) {
            log(opName(op) + ": rejected after every kind of reset (result " + sent + "). " + transport.describe());
            throw new DriveUnresponsiveException("The stick stopped responding. Unplug it, plug it in again and retry.");
        }

        int moved = 0;
        if (length > 0 && data != null) {
            boolean failed = false;
            while (moved < length) {
                int want = Math.min(maxTransfer, length - moved);
                int n = dataIn
                        ? transport.bulkIn(data, offset + moved, want, timeoutMs())
                        : transport.bulkOut(data, offset + moved, want, timeoutMs());
                if (n < 0) {
                    log(opName(op) + ": data phase error (result " + n + ") after " + moved + " of " + length + " bytes");
                    failed = true;
                    break;
                }
                moved += n;
                if (n < want) {
                    log(opName(op) + ": short transfer, " + n + " of " + want + " bytes");
                    break; // the status block follows
                }
            }
            if (failed) {
                log("endpoint status: out=" + transport.endpointStatus(false) + " in=" + transport.endpointStatus(true)
                        + " (1 means halted, -1 unknown)");
                transport.clearHalt(dataIn);
            }
        }

        byte[] csw = new byte[13];
        int got = transport.bulkIn(csw, 0, csw.length, timeoutMs());
        if (got != csw.length) {
            log(opName(op) + ": status read returned " + got + "; clearing halt and reading again");
            transport.clearHalt(true);
            got = transport.bulkIn(csw, 0, csw.length, timeoutMs());
        }
        if (got != csw.length) {
            log(opName(op) + ": status read failed again (result " + got + "); resynchronising both endpoints");
            transport.resync();
            got = transport.bulkIn(csw, 0, csw.length, COMMAND_TIMEOUT_MS);
        }
        if (got == csw.length && getLe(csw, 0, 4) == CSW_SIGNATURE && getLe(csw, 4, 4) != myTag) {
            // Most likely a leftover status block of an earlier command; the real one should follow
            log(opName(op) + ": stale status block (tag " + getLe(csw, 4, 4) + ", expected " + myTag + "); reading again");
            got = transport.bulkIn(csw, 0, csw.length, timeoutMs());
        }
        if (got != csw.length || getLe(csw, 0, 4) != CSW_SIGNATURE || getLe(csw, 4, 4) != myTag) {
            log(opName(op) + ": invalid status block, read=" + got + " bytes=" + hex(csw) + " expectedTag=" + myTag
                    + " (moved " + moved + " of " + length + "). " + transport.describe());
            transport.reset();
            throw new IOException("The stick returned an invalid status");
        }

        int status = csw[12] & 0xFF;
        boolean readWrite = op == 0x28 || op == 0x2A || op == 0x88 || op == 0x8A;
        if (readWrite) {
            readWritesLogged++;
        }
        boolean routinePing = op == 0x00 && status == 0 && !initializing;
        if (!routinePing && (!readWrite || readWritesLogged <= LOGGED_READ_WRITES || status != 0 || moved != length)) {
            log(opName(op) + " " + (dataIn ? "in" : "out") + " " + length + " bytes -> status=" + status + " moved=" + moved);
        }
        return new Result(status, moved);
    }

    private static String opName(int op) {
        switch (op) {
            case 0x00: return "TEST UNIT READY";
            case 0x03: return "REQUEST SENSE";
            case 0x12: return "INQUIRY";
            case 0x1A: return "MODE SENSE";
            case 0x25: return "READ CAPACITY(10)";
            case 0x28: return "READ(10)";
            case 0x2A: return "WRITE(10)";
            case 0x35: return "SYNC CACHE";
            case 0x88: return "READ(16)";
            case 0x8A: return "WRITE(16)";
            case 0x9E: return "READ CAPACITY(16)";
            default: return String.format(Locale.US, "op 0x%02x", op);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format(Locale.US, "%02x ", x & 0xFF));
        }
        return s.toString().trim();
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static void putLe(byte[] b, int offset, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            b[offset + i] = (byte) (value >> (8 * i));
        }
    }

    private static long getLe(byte[] b, int offset, int bytes) {
        long v = 0;
        for (int i = bytes - 1; i >= 0; i--) {
            v = (v << 8) | (b[offset + i] & 0xFF);
        }
        return v;
    }

    private static void putBe(byte[] b, int offset, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            b[offset + i] = (byte) (value >> (8 * (bytes - 1 - i)));
        }
    }

    private static long getBe(byte[] b, int offset, int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v = (v << 8) | (b[offset + i] & 0xFF);
        }
        return v;
    }
}
