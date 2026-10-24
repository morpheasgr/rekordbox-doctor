#!/bin/bash
# Tries the drive code of the app on image files and checks the results
# with fsck.fat, fsck.exfat, sfdisk and mtools (packages dosfstools,
# exfatprogs, fdisk and mtools). Images go to a temporary folder.
set -e
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src=app/src/main/java/org/morpheas/rbdoctor/drive
javac -d "$work/classes" $(ls $src/*.java | grep -v UsbDisks) tools/drive-test/DriveTest.java
run() { java -cp "$work/classes" DriveTest "$@" 2> >(grep -v "^Picked up" >&2); }
MiB=$((1024 * 1024))

# Copies the start of partition 1 into its own sparse file, for the fsck
# tools: the FATs and the root directory (the first 2 GB), the rest of a
# fresh volume is empty
partition() {
    local image=$1 out=$2 start size
    start=$(sfdisk -d "$image" | awk -F'[=,]' '/start=/ {gsub(/ /,""); print $2; exit}')
    size=$(sfdisk -d "$image" | awk -F'[=,]' '/size=/ {gsub(/ /,""); print $4; exit}')
    dd if="$image" of="$out" bs=512 skip="$start" count=$((size < 4194304 ? size : 4194304)) conv=sparse status=none
    truncate -s $((size * 512)) "$out"
}

check_fat32() {
    local image=$1 label=$2
    sfdisk -V "$image" >/dev/null
    sfdisk -d "$image" | grep -q 'type=c' || { echo "FAIL: partition type is not c"; exit 1; }
    partition "$image" "$work/part"
    fsck.fat -n "$work/part" | tail -1
    echo "a test file" > "$work/file.txt"
    mcopy -i "$work/part" "$work/file.txt" ::/FILE.TXT
    mdir -i "$work/part" :: | grep -q "FILE     TXT" || { echo "FAIL: copied file missing"; exit 1; }
    [ -z "$label" ] || mlabel -i "$work/part" -s :: | grep -q "$label" || { echo "FAIL: label"; exit 1; }
    fsck.fat -n "$work/part" | tail -1
    echo "OK"
}

echo "== Android-formatted 64 GB stick: exFAT under type 0x0C"
truncate -s 64G "$work/a.img"
echo 'start=2048, type=c' | sfdisk -q "$work/a.img"
size=$(sfdisk -d "$work/a.img" | awk -F'[=,]' '/size=/ {gsub(/ /,""); print $4; exit}')
truncate -s $((size * 512)) "$work/a.part"
mkfs.exfat -q -L MUSIC "$work/a.part" >/dev/null
dd if="$work/a.part" of="$work/a.img" bs=1M seek=1 conv=notrunc,sparse status=none
cmp -s <(head -c 4M "$work/a.part" | md5sum) <(tail -c +$((MiB + 1)) "$work/a.img" | head -c 4M | md5sum)
run inspect "$work/a.img"
run fix "$work/a.img" 1
sfdisk -d "$work/a.img" | grep -q 'type=7' && echo "sfdisk: type=7"
dd if="$work/a.img" of="$work/a.check" bs=1M skip=1 conv=sparse status=none
cmp -s "$work/a.part" "$work/a.check" && echo "the file system is untouched"
fsck.exfat -n "$work/a.check" | tail -1
rm -f "$work"/a.*

echo "== FAT32 under type 0x07"
truncate -s 2G "$work/b.img"
echo 'start=2048, type=7' | sfdisk -q "$work/b.img"
partition "$work/b.img" "$work/b.part"
mkfs.fat -F 32 -n PIONEER "$work/b.part" >/dev/null
dd if="$work/b.part" of="$work/b.img" bs=1M seek=1 conv=notrunc,sparse status=none
run inspect "$work/b.img"
run fix "$work/b.img" 1
sfdisk -d "$work/b.img" | grep -q 'type=c' && echo "sfdisk: type=c"
rm -f "$work"/b.*

echo "== GPT"
truncate -s 1G "$work/c.img"
printf 'label: gpt\nstart=2048, type=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7\n' | sfdisk -q "$work/c.img"
partition "$work/c.img" "$work/c.part"
mkfs.fat -F 32 "$work/c.part" >/dev/null
dd if="$work/c.part" of="$work/c.img" bs=1M seek=1 conv=notrunc,sparse status=none
run inspect "$work/c.img"
rm -f "$work"/c.*

echo "== No partition table"
truncate -s 1G "$work/d.img"
mkfs.fat -F 32 "$work/d.img" >/dev/null
run inspect "$work/d.img"
rm -f "$work"/d.*

for size in 128M 4G 64G 500G 3T; do
    echo "== Format $size"
    truncate -s $size "$work/e.img"
    # leftovers the format must clear: a GPT at both ends
    printf 'label: gpt\nstart=2048, type=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7\n' | sfdisk -q "$work/e.img"
    run format "$work/e.img" "rekordbox"
    check_fat32 "$work/e.img" REKORDBOX
    rm -f "$work"/e.* "$work/part"
done

echo "== Format through the USB layer, with a stalled write"
truncate -s 300M "$work/f.img"
run usb-format "$work/f.img" ""
check_fat32 "$work/f.img" ""
