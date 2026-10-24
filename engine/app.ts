// The engine of the app: checks and rebuilds an export.pdb with the code of
// rekordbox-usb-repair (engine/upstream, unmodified). Bundled into
// app/src/main/assets/engine.js by tools/build-engine.sh, where it runs in
// the WebView of the app. Files go in and out as base64, the bridge to Java
// only passes strings.
import { Buffer } from 'buffer'
import { Database } from './upstream/pdb/pdbReader'
import { scanPdbPages } from './upstream/pdb/pdbPageScanner'
import { rebuildPdbFromScan } from './upstream/pdb/pdbRebuilder'
import { findMisalignedStrings } from './upstream/pdb/cdjSafety'

export interface Counts {
  tracks: number
  playlists: number
  playlistEntries: number
}

export interface CheckResult {
  readable: boolean
  // Why the file can't be read normally
  error?: string
  counts?: Counts
  // Tables that are damaged, as the reader describes them
  damaged: string[]
  // Long strings off a 4-byte boundary: these freeze the XDJ-700 v1.15 and
  // CDJ-2000NXS2 on insert
  misaligned: number
  // The music files the library lists, relative to the stick, e.g.
  // "/Contents/Artist/Album/Track.mp3"
  filePaths: string[]
  // What a repair would give, from a dry run of it
  repairable: boolean
  recoverable?: Counts
  verdict: 'healthy' | 'damaged' | 'freezes' | 'unreadable'
}

export interface RepairResult {
  ok: boolean
  // Why the rebuilt file wasn't accepted; nothing is to be written then
  problems: string[]
  before?: Counts
  after?: Counts
  notes: string[]
  // The rebuilt export.pdb, base64, when ok
  output?: string
}

function counts(db: Database): Counts {
  return {
    tracks: db.tracks.length,
    playlists: db.playlistTree.length,
    playlistEntries: db.playlistEntries.length,
  }
}

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

export function check(base64: string): CheckResult {
  const src = Buffer.from(base64, 'base64')
  const dryRun = repair(base64)
  const repairable = dryRun.ok
  const recoverable = dryRun.after
  let db: Database
  try {
    db = new Database(src)
  } catch (err) {
    return { readable: false, error: errorText(err), damaged: [], misaligned: 0, filePaths: [], repairable, recoverable, verdict: 'unreadable' }
  }
  const damaged = [...db.tableErrors.values()]
  const found = counts(db)
  // A damaged table directory hides whole tables without any error; the
  // rows the page scan finds show it
  if (recoverable && recoverable.tracks > found.tracks) {
    damaged.push(`${recoverable.tracks - found.tracks} of ${recoverable.tracks} tracks can't be read`)
  }
  if (recoverable && recoverable.playlists > found.playlists) {
    damaged.push(`${recoverable.playlists - found.playlists} of ${recoverable.playlists} playlists can't be read`)
  }
  if (!found.tracks && !damaged.length) {
    damaged.push('the library has no tracks')
  }
  const misaligned = findMisalignedStrings(src, db).total
  return {
    readable: true,
    counts: found,
    damaged,
    misaligned,
    filePaths: db.tracks.map((track) => track.filePath).filter((path) => !!path),
    repairable,
    recoverable,
    verdict: damaged.length > 0 ? 'damaged' : misaligned > 0 ? 'freezes' : 'healthy',
  }
}

// Same steps and checks as cli/make-cdj-safe.ts of rekordbox-usb-repair
export function repair(base64: string): RepairResult {
  const src = Buffer.from(base64, 'base64')
  let before: Database | null = null
  try {
    before = new Database(src)
  } catch {
    // A corrupted file may not read normally at all; the rebuild doesn't
    // need it to
    before = null
  }
  let out: Buffer
  let notes: string[]
  try {
    const rebuilt = rebuildPdbFromScan(scanPdbPages(src))
    out = rebuilt.buffer
    notes = rebuilt.summary.notes
  } catch (err) {
    return { ok: false, problems: [`the rebuild failed: ${errorText(err)}`], notes: [] }
  }
  let after: Database
  try {
    after = new Database(out)
  } catch (err) {
    return { ok: false, problems: [`the rebuilt file can't be read: ${errorText(err)}`], notes }
  }
  const stillMisaligned = findMisalignedStrings(out, after).total
  const problems: string[] = []
  if (after.tableErrors.size) {
    problems.push(`the rebuilt file has damaged tables: ${[...after.tableErrors.values()].join('; ')}`)
  }
  if (before && after.tracks.length < before.tracks.length) {
    problems.push(`tracks would be lost: ${before.tracks.length} -> ${after.tracks.length}`)
  }
  if (before && after.playlistTree.length < before.playlistTree.length) {
    problems.push(`playlists would be lost: ${before.playlistTree.length} -> ${after.playlistTree.length}`)
  }
  if (!after.tracks.length) {
    problems.push('no tracks could be recovered')
  }
  if (stillMisaligned) {
    problems.push(`${stillMisaligned} strings are still misaligned`)
  }
  return {
    ok: problems.length === 0,
    problems,
    before: before ? counts(before) : undefined,
    after: counts(after),
    notes,
    output: problems.length === 0 ? out.toString('base64') : undefined,
  }
}
