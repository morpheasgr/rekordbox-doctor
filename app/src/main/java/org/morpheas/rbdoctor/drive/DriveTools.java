// The partitioner and the FAT32 formatter are ported from usbformat
// (https://github.com/blackorange-sketch/usbformat), MIT licensed, see
// third_party/usbformat/LICENSE. Only MBR and FAT32 are kept: what Pioneer
// players read.
package org.morpheas.rbdoctor.drive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/** What the app writes to a stick: a fixed partition type, or a fresh MBR with FAT32. */
public final class DriveTools {
    private static final long MIN_CLUSTERS = 65525L;
    private static final long MAX_CLUSTERS = 0x0FFFFFF5L;

    private DriveTools() {
    }

    /**
     * Sets the type of an MBR partition to the one of its file system,
     * leaving everything else on the stick as it is. Returns the new type.
     */
    public static int fixPartitionType(Disk disk, int slot) throws IOException {
        DriveInfo info = DriveInfo.inspect(disk);
        if (info.table != DriveInfo.Table.MBR) {
            throw new IOException("The stick has no MBR partition table");
        }
        DriveInfo.Partition partition = null;
        for (DriveInfo.Partition p : info.partitions) {
            if (p.slot == slot) {
                partition = p;
            }
        }
        if (partition == null || !partition.typeMismatch()) {
            throw new IOException("Partition " + slot + " needs no fix");
        }
        int type = partition.expectedType();
        byte[] s0 = disk.read(0, 1);
        int at = 446 + 16 * (slot - 1) + 4;
        DriveLog.log(String.format(Locale.US, "fix: partition %d type 0x%02x -> 0x%02x (%s)",
                slot, s0[at] & 0xFF, type, partition.fs));
        s0[at] = (byte) type;
        disk.write(0, s0);
        disk.flush();
        if (!Arrays.equals(disk.read(0, 1), s0)) {
            throw new IOException("The partition table read back differs from what was written");
        }
        return type;
    }

    /**
     * Erases the stick: an MBR with one FAT32 partition (type 0x0C) over the
     * whole stick, aligned to 1 MiB. FAT32 works for any size here, not only
     * up to 32 GB like in Windows.
     */
    public static void formatFat32(Disk disk, String label) throws IOException {
        int ss = disk.sectorSize();
        long total = disk.sectorCount();
        long align = Disk.MIB / ss;
        if (total * ss < 64L * Disk.MIB) {
            throw new IOException("The stick is smaller than 64 MB");
        }
        long start = align;
        long size = total - start;
        size -= size % align;
        // An MBR addresses at most 2^32 - 1 sectors, 2 TB
        size = Math.min(size, 0xFFFFFFFFL - start);
        size -= size % align;
        DriveLog.log("format: FAT32 label='" + label + "' disk=" + total + " sectors of " + ss + " bytes, partition at "
                + start + " of " + size + " sectors");

        // Leftovers of earlier tables: the first MiB and the last MiB (a
        // backup GPT). Sector 0 is written last, so the stick holds a
        // complete file system before the table points to it.
        disk.zero(1, start - 1);
        long tail = Math.min(align, total - start);
        disk.zero(total - tail, tail);

        formatFat32Volume(disk, start, size, label);

        byte[] mbr = new byte[ss];
        // Windows identifies disks by this signature, so it must not be zero
        long signature = (new SecureRandom().nextInt() & 0xFFFFFFFFL) | 1L;
        putLe32(mbr, 440, signature);
        int o = 446;
        mbr[o + 1] = (byte) 0xFE;
        mbr[o + 2] = (byte) 0xFF;
        mbr[o + 3] = (byte) 0xFF;
        mbr[o + 4] = 0x0C;
        mbr[o + 5] = (byte) 0xFE;
        mbr[o + 6] = (byte) 0xFF;
        mbr[o + 7] = (byte) 0xFF;
        putLe32(mbr, o + 8, start);
        putLe32(mbr, o + 12, size);
        mbr[510] = 0x55;
        mbr[511] = (byte) 0xAA;
        DriveLog.log("format: writing sector 0");
        disk.write(0, mbr);
        disk.flush();
        DriveLog.log("format: finished");
    }

    private static void formatFat32Volume(Disk disk, long startLba, long sectors, String label) throws IOException {
        int ss = disk.sectorSize();
        long total = Math.min(sectors, 0xFFFFFFFFL);
        long volBytes = total * ss;

        // The cluster size follows the Microsoft table for 512-byte sectors
        int clusterBytes;
        if (volBytes <= 260L * Disk.MIB) {
            clusterBytes = 512;
        } else if (volBytes <= 8L * 1024 * Disk.MIB) {
            clusterBytes = 4096;
        } else if (volBytes <= 16L * 1024 * Disk.MIB) {
            clusterBytes = 8192;
        } else if (volBytes <= 32L * 1024 * Disk.MIB) {
            clusterBytes = 16384;
        } else {
            clusterBytes = 32768;
        }
        int spc = Math.max(1, clusterBytes / ss);
        long reserved = 32;

        long fat = fatSectorsFor(total, ss, spc, reserved);
        long clusters = (total - reserved - 2 * fat) / spc;
        while (clusters < MIN_CLUSTERS && spc > 1) {
            spc /= 2;
            fat = fatSectorsFor(total, ss, spc, reserved);
            clusters = (total - reserved - 2 * fat) / spc;
        }
        while (clusters > MAX_CLUSTERS && spc < 128) {
            spc *= 2;
            fat = fatSectorsFor(total, ss, spc, reserved);
            clusters = (total - reserved - 2 * fat) / spc;
        }
        if (clusters < MIN_CLUSTERS || clusters > MAX_CLUSTERS) {
            throw new IOException("The stick's size doesn't suit FAT32");
        }
        // The data area on a 1 MiB boundary (the partition starts on one)
        long align = Disk.MIB / ss;
        long extra = (align - (reserved + 2 * fat) % align) % align;
        if ((total - reserved - extra - 2 * fat) / spc >= MIN_CLUSTERS) {
            reserved += extra;
            clusters = (total - reserved - 2 * fat) / spc;
        }
        DriveLog.log("fat32: cluster=" + (spc * ss) + " B reserved=" + reserved + " fat=" + fat
                + " sectors (x2) clusters=" + clusters + " total=" + total + " sectors");
        byte[] labelBytes = fatLabel(label);
        long volumeId = System.nanoTime() & 0xFFFFFFFFL;

        byte[] boot = new byte[ss];
        boot[0] = (byte) 0xEB;
        boot[1] = 0x58;
        boot[2] = (byte) 0x90;
        putAscii(boot, 3, "MSDOS5.0");
        putLe16(boot, 11, ss);
        boot[13] = (byte) spc;
        putLe16(boot, 14, (int) reserved);
        boot[16] = 2; // FATs
        boot[21] = (byte) 0xF8; // fixed media
        putLe16(boot, 24, 63); // sectors per track
        putLe16(boot, 26, 255); // heads
        putLe32(boot, 28, Math.min(startLba, 0xFFFFFFFFL)); // hidden sectors
        putLe32(boot, 32, total);
        putLe32(boot, 36, fat);
        putLe32(boot, 44, 2); // root directory cluster
        putLe16(boot, 48, 1); // FSInfo sector
        putLe16(boot, 50, 6); // backup boot sector
        boot[64] = (byte) 0x80; // drive number
        boot[66] = 0x29; // extended boot signature
        putLe32(boot, 67, volumeId);
        System.arraycopy(labelBytes != null ? labelBytes : "NO NAME    ".getBytes(StandardCharsets.US_ASCII), 0, boot, 71, 11);
        putAscii(boot, 82, "FAT32   ");
        boot[510] = 0x55;
        boot[511] = (byte) 0xAA;

        byte[] info = new byte[ss];
        putLe32(info, 0, 0x41615252L);
        putLe32(info, 484, 0x61417272L);
        putLe32(info, 488, clusters - 1); // free clusters: all but the root directory
        putLe32(info, 492, 3); // next free cluster
        putLe32(info, 508, 0xAA550000L);

        byte[] third = new byte[ss];
        third[510] = 0x55;
        third[511] = (byte) 0xAA;

        byte[] head = new byte[ss * 3];
        System.arraycopy(boot, 0, head, 0, ss);
        System.arraycopy(info, 0, head, ss, ss);
        System.arraycopy(third, 0, head, 2 * ss, ss);

        long dataStart = reserved + 2 * fat;
        // The reserved area, both FATs and the root directory cluster start clean
        disk.zero(startLba, dataStart + spc);
        disk.write(startLba, head);
        disk.write(startLba + 6, head);

        byte[] fatHead = new byte[ss];
        putLe32(fatHead, 0, 0x0FFFFFF8L);
        putLe32(fatHead, 4, 0x0FFFFFFFL);
        putLe32(fatHead, 8, 0x0FFFFFFFL); // the root directory, one cluster
        disk.write(startLba + reserved, fatHead);
        disk.write(startLba + reserved + fat, fatHead);

        if (labelBytes != null) {
            byte[] root = new byte[ss];
            System.arraycopy(labelBytes, 0, root, 0, 11);
            root[11] = 0x08; // volume label
            disk.write(startLba + dataStart, root);
        }
    }

    private static long fatSectorsFor(long total, int ss, int perCluster, long reserved) {
        long f = 1;
        while (true) {
            long c = (total - reserved - 2 * f) / perCluster;
            long need = ((c + 2) * 4 + ss - 1) / ss;
            if (need <= f) {
                return f;
            }
            f = need;
        }
    }

    /** ASCII only, upper case, at most 11 characters; null when nothing usable is left. */
    public static byte[] fatLabel(String label) {
        String forbidden = "\"*+,./:;<=>?[\\]|";
        StringBuilder cleaned = new StringBuilder();
        for (char c : label.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 0x20 && c <= 0x7E && forbidden.indexOf(c) < 0 && cleaned.length() < 11) {
                cleaned.append(c);
            }
        }
        String text = cleaned.toString().replaceAll("\\s+$", "");
        if (text.isEmpty()) {
            return null;
        }
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < 11) {
            padded.append(' ');
        }
        return padded.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void putLe16(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >> 8);
    }

    private static void putLe32(byte[] b, int offset, long value) {
        for (int i = 0; i < 4; i++) {
            b[offset + i] = (byte) (value >> (8 * i));
        }
    }

    private static void putAscii(byte[] b, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            b[offset + i] = (byte) text.charAt(i);
        }
    }
}
