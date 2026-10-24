# Source

The drive tools in `app/src/main/java/org/morpheas/rbdoctor/drive` are ported
to Java from https://github.com/blackorange-sketch/usbformat at
13eb627, MIT licensed, see LICENSE:
the SCSI over USB Bulk-Only layer (`ScsiDisk`, `UsbDisks`, `DriveSession`),
the reading of partition tables (`DriveInfo`), and the MBR partitioner and
FAT32 formatter (`DriveTools`), with the GPT, exFAT and NTFS parts left out.
The fake USB stick of `tools/drive-test` is ported from its tests.
