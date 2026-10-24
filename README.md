# Rekordbox Doctor

An Android app that checks and repairs the library rekordbox exports to a USB
stick: `PIONEER/rekordbox/export.pdb`, the file CDJs and XDJs read. Plug the
stick into the phone with an OTG adapter, open it in the app, and it says
whether the library is:

- **healthy**;
- **damaged**: tables or pages can't be read, as after a stick was pulled out
  mid-export;
- **freezing some players**: long strings off a 4-byte boundary, which freezes
  an XDJ-700 (firmware 1.15) or CDJ-2000NXS2 on insert;
- **unreadable**.

A repair rebuilds the file from every row that can still be read. The
rebuilt file must read back cleanly with no tracks or playlists lost, or
nothing is written. Before the original is replaced, it is backed up twice:
in the app's folder on the phone, and in a `Rekordbox Doctor backups` folder
on the stick. A single `export.pdb` can also be opened, and a repaired copy
saved anywhere.

It also checks the stick itself, below the files: the partition table and the
file system. Pioneer players read MBR sticks with FAT32 (or FAT16), and
Android formats sticks larger than 32 GB as exFAT, under the partition type
of FAT32. The app can set the partition type to match the file system,
changing that one byte only, or format the stick as FAT32, at any size. For
this it talks to the stick over USB directly (the USB host API, no root), so
Android unmounts the stick until the app gives it back. Plug the stick in
while the app is open: one Android mounted earlier may not be taken over
cleanly.

Only `export.pdb` is checked. `exportLibrary.db`, the library of newer
players such as the CDJ-3000, isn't.

## How it works

The engine is the one of
[rekordbox-usb-repair](https://github.com/themisterchong/rekordbox-usb-repair)
by themisterchong (MIT), in `engine/upstream`, see its `SOURCE.md`.
`engine/app.ts` wraps it in `check()` and `repair()`, with the checks of the
upstream command line tool. `tools/build-engine.sh` bundles it into
`app/src/main/assets/engine.js`, which runs in a WebView next to the page of
the app, `app/src/main/assets/index.html`. `MainActivity.java` opens the
stick through the storage access framework and writes the result back. No
permissions are needed.

The drive tools in `app/src/main/java/org/morpheas/rbdoctor/drive` are ported
from [usbformat](https://github.com/blackorange-sketch/usbformat) (MIT), see
`third_party/usbformat`. `tools/drive-test/run.sh` tries them on a PC, on
image files and a fake USB stick, and checks the results with `fsck.fat`,
`fsck.exfat`, `sfdisk` and `mtools`.

The bundle is committed, so building the app needs no Node:

    ./gradlew assembleDebug

After changing `engine/`:

    ./tools/build-engine.sh

`index.html` also works in a desktop browser, with a file picker in place of
the app, for trying the engine on a file.

Not affiliated with AlphaTheta or Pioneer DJ. rekordbox is their trademark.
