// The reading of partition tables and file systems is ported from usbformat
// (https://github.com/blackorange-sketch/usbformat), MIT licensed, see
// third_party/usbformat/LICENSE.
package org.morpheas.rbdoctor.drive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A read-only look at a drive: partition table, partitions, their file systems. */
public final class DriveInfo {
    public enum Table { MBR, GPT, NONE }

    public static final class Partition {
        /** 1-4 in an MBR, the entry number in a GPT */
        public final int slot;
        /** The MBR partition type, -1 in a GPT or without a table */
        public final int type;
        public final long start;
        public final long sectors;
        /** "FAT32", "exFAT", ...; null when not recognised */
        public final String fs;
        /** The label in the boot sector, "" when none or unknown */
        public final String label;

        Partition(int slot, int type, long start, long sectors, String fs, String label) {
            this.slot = slot;
            this.type = type;
            this.start = start;
            this.sectors = sectors;
            this.fs = fs;
            this.label = label;
        }

        /** The MBR type the file system should have, -1 when unknown */
        public int expectedType() {
            return DriveInfo.expectedType(fs);
        }

        /** An MBR partition whose type doesn't match its file system */
        public boolean typeMismatch() {
            if (type < 0 || fs == null) {
                return false;
            }
            switch (fs) {
                case "FAT32":
                    return type != 0x0B && type != 0x0C;
                case "FAT16":
                    return type != 0x04 && type != 0x06 && type != 0x0E;
                case "FAT12":
                    return type != 0x01;
                case "exFAT":
                case "NTFS":
                    return type != 0x07;
                case "ext2/3/4":
                    return type != 0x83;
                default:
                    return false;
            }
        }
    }

    public final int sectorSize;
    public final long sectorCount;
    public final Table table;
    public final List<Partition> partitions;

    private DriveInfo(int sectorSize, long sectorCount, Table table, List<Partition> partitions) {
        this.sectorSize = sectorSize;
        this.sectorCount = sectorCount;
        this.table = table;
        this.partitions = partitions;
    }

    static int expectedType(String fs) {
        if (fs == null) {
            return -1;
        }
        switch (fs) {
            case "FAT32":
                return 0x0C; // FAT32 with LBA addressing
            case "FAT16":
                return 0x0E; // FAT16 with LBA addressing
            case "FAT12":
                return 0x01;
            case "exFAT":
            case "NTFS":
                return 0x07;
            case "ext2/3/4":
                return 0x83;
            default:
                return -1;
        }
    }

    public static String typeName(int type) {
        switch (type) {
            case 0x01: return "FAT12";
            case 0x04: return "FAT16 (small)";
            case 0x06: return "FAT16";
            case 0x07: return "exFAT/NTFS";
            case 0x0B: return "FAT32 (CHS)";
            case 0x0C: return "FAT32";
            case 0x0E: return "FAT16";
            case 0x0F: return "extended";
            case 0x83: return "Linux";
            case 0xEE: return "GPT protective";
            default: return "unknown";
        }
    }

    public static DriveInfo inspect(Disk disk) throws IOException {
        int ss = disk.sectorSize();
        long total = disk.sectorCount();
        List<Partition> parts = new ArrayList<>();

        // A file system right at sector 0 means there is no partition table
        byte[] first = head(disk, 0);
        String direct = detectFs(first);
        if (direct != null) {
            parts.add(new Partition(1, -1, 0, total, direct, label(first, direct)));
            return new DriveInfo(ss, total, Table.NONE, parts);
        }
        byte[] s0 = disk.read(0, 1);
        if (u8(s0, 510) != 0x55 || u8(s0, 511) != 0xAA) {
            return new DriveInfo(ss, total, Table.NONE, parts);
        }
        int[] status = new int[4];
        int[] type = new int[4];
        long[] start = new long[4];
        long[] size = new long[4];
        boolean gpt = false;
        for (int i = 0; i < 4; i++) {
            int o = 446 + 16 * i;
            status[i] = u8(s0, o);
            type[i] = u8(s0, o + 4);
            start[i] = le32(s0, o + 8);
            size[i] = le32(s0, o + 12);
            // Boot flags other than 0x00 / 0x80 mean this is no real MBR
            if (status[i] != 0x00 && status[i] != 0x80) {
                return new DriveInfo(ss, total, Table.NONE, parts);
            }
            gpt |= type[i] == 0xEE;
        }
        if (gpt) {
            byte[] header = disk.read(1, 1);
            if (new String(header, 0, 8, StandardCharsets.US_ASCII).equals("EFI PART")) {
                return readGpt(disk, header);
            }
        }
        for (int i = 0; i < 4; i++) {
            if (type[i] != 0 && size[i] > 0) {
                byte[] h = start[i] < total ? head(disk, start[i]) : new byte[0];
                String fs = detectFs(h);
                parts.add(new Partition(i + 1, type[i], start[i], size[i], fs, label(h, fs)));
            }
        }
        return new DriveInfo(ss, total, Table.MBR, parts);
    }

    private static DriveInfo readGpt(Disk disk, byte[] header) throws IOException {
        int ss = disk.sectorSize();
        long total = disk.sectorCount();
        long entriesLba = le64(header, 72);
        int count = (int) Math.max(0, Math.min(256, le32(header, 80)));
        int entrySize = (int) le32(header, 84);
        List<Partition> parts = new ArrayList<>();
        if (entrySize >= 128 && entrySize <= 1024 && entriesLba >= 2 && entriesLba < total) {
            int sectors = (int) Math.min((count * entrySize + ss - 1) / ss, total - entriesLba);
            if (sectors > 0) {
                byte[] raw = disk.read(entriesLba, sectors);
                for (int i = 0; i < count; i++) {
                    int o = i * entrySize;
                    if (o + 48 > raw.length) {
                        break;
                    }
                    boolean unused = true;
                    for (int k = 0; k < 16; k++) {
                        unused &= raw[o + k] == 0;
                    }
                    long firstLba = le64(raw, o + 32);
                    long lastLba = le64(raw, o + 40);
                    if (unused || lastLba < firstLba) {
                        continue;
                    }
                    byte[] h = firstLba < total ? head(disk, firstLba) : new byte[0];
                    String fs = detectFs(h);
                    parts.add(new Partition(i + 1, -1, firstLba, lastLba - firstLba + 1, fs, label(h, fs)));
                }
            }
        }
        return new DriveInfo(ss, total, Table.GPT, parts);
    }

    // Enough sectors from lba to cover the ext superblock magic (byte 1082)
    private static byte[] head(Disk disk, long lba) throws IOException {
        int want = (1082 + disk.sectorSize() - 1) / disk.sectorSize();
        int n = (int) Math.min(want, disk.sectorCount() - lba);
        return n <= 0 ? new byte[0] : disk.read(lba, n);
    }

    static String detectFs(byte[] b) {
        if (b.length < 512) {
            return null;
        }
        String oem = text(b, 3, 8);
        if (oem.equals("EXFAT   ")) {
            return "exFAT";
        }
        if (oem.equals("NTFS    ")) {
            return "NTFS";
        }
        if (text(b, 82, 8).equals("FAT32   ")) {
            return "FAT32";
        }
        String legacy = text(b, 54, 8);
        if (legacy.equals("FAT16   ")) {
            return "FAT16";
        }
        if (legacy.equals("FAT12   ")) {
            return "FAT12";
        }
        if (b.length >= 1082 && u8(b, 1080) == 0x53 && u8(b, 1081) == 0xEF) {
            return "ext2/3/4";
        }
        return null;
    }

    // The label in the boot sector of FAT; exFAT keeps it in the root
    // directory only
    private static String label(byte[] b, String fs) {
        if (fs == null) {
            return "";
        }
        String text;
        if (fs.equals("FAT32")) {
            text = text(b, 71, 11);
        } else if (fs.equals("FAT16") || fs.equals("FAT12")) {
            text = text(b, 43, 11);
        } else {
            return "";
        }
        text = text.trim();
        return text.equals("NO NAME") ? "" : text;
    }

    /** What keeps CDJs and XDJs from reading the stick, empty when nothing */
    public List<String> playerProblems() {
        List<String> problems = new ArrayList<>();
        if (table == Table.GPT) {
            problems.add("The partition table is GPT. Pioneer players read sticks with an MBR partition table.");
        }
        if (table == Table.NONE && partitions.isEmpty()) {
            problems.add("Neither a partition table nor a file system was found.");
        } else if (table == Table.NONE) {
            problems.add("There is no partition table: the file system starts at the very beginning of the stick. Not every player reads that.");
        }
        if (table != Table.NONE && partitions.isEmpty()) {
            problems.add("The partition table lists no partitions.");
        }
        if (!partitions.isEmpty()) {
            Partition p = partitions.get(0);
            if (p.fs == null) {
                problems.add("The first partition has no file system that could be recognised.");
            } else if (p.fs.equals("exFAT")) {
                problems.add("The file system is exFAT. Most Pioneer players read only FAT32 (or FAT16): only a few recent ones read exFAT.");
            } else if (!p.fs.startsWith("FAT")) {
                problems.add("The file system is " + p.fs + ", which Pioneer players don't read.");
            }
            for (Partition q : partitions) {
                if (q.typeMismatch()) {
                    problems.add(String.format(Locale.US,
                            "Partition %d holds %s but its type in the partition table is 0x%02X (%s), not 0x%02X. Players and computers may not recognise it.",
                            q.slot, q.fs, q.type, typeName(q.type), q.expectedType()));
                }
            }
        }
        if (sectorSize != 512) {
            problems.add("The stick has " + sectorSize + "-byte sectors; players expect 512.");
        }
        return problems;
    }

    private static String text(byte[] b, int offset, int length) {
        return new String(b, offset, length, StandardCharsets.ISO_8859_1);
    }

    static int u8(byte[] b, int o) {
        return b[o] & 0xFF;
    }

    static long le32(byte[] b, int o) {
        long v = 0;
        for (int i = 3; i >= 0; i--) {
            v = (v << 8) | u8(b, o + i);
        }
        return v;
    }

    private static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | u8(b, o + i);
        }
        return v;
    }
}
