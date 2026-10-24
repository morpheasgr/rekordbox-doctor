# Source

Copied from https://github.com/themisterchong/rekordbox-usb-repair at
c03cd9a (`src/engine/pdb`, `src/engine/testing/corruptPdb.ts`), MIT
licensed, see LICENSE.

All files are unmodified except `pdb/pdbEditor.ts`, which carries
`pdbEditor-rowcache.patch`. A rebuild appends thousands of rows, and the
editor reparsed the whole file for each lookup of `addTrack()`,
`getOrCreate*()` and `addToPlaylist()`. That took 15 s for a library of
1337 tracks on a PC, and over a minute in the WebView. The patch keeps the
rows of each table those lookups read across appends to other tables. It
brings the rebuild to 0.2 s, with output byte for byte the same as
unpatched on the 19 exports and corrupted variants it was tried on.
