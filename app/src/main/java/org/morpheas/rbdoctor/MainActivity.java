package org.morpheas.rbdoctor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Insets;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.morpheas.rbdoctor.drive.DriveInfo;
import org.morpheas.rbdoctor.drive.DriveLog;
import org.morpheas.rbdoctor.drive.DriveSession;
import org.morpheas.rbdoctor.drive.DriveTools;
import org.morpheas.rbdoctor.drive.ScsiDisk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The app is a page in a WebView (assets/index.html), where the engine of
// rekordbox-usb-repair checks and rebuilds export.pdb (assets/engine.js).
// This activity gives the page the files: it opens the stick or a single
// file through the storage access framework and writes the repaired file
// back. Files cross the bridge as base64, the bridge only passes strings.
// The drive tools (package drive) talk to the stick over USB directly
// instead, below the file system: they fix the partition type and format.
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "org.morpheas.rbdoctor.USB_PERMISSION";

    private static final int PICK_STICK = 1;
    private static final int PICK_FILE = 2;
    private static final int SAVE_FILE = 3;

    private static final String PDB_NAME = "export.pdb";
    private static final String BACKUP_DIR_NAME = "Rekordbox Doctor backups";

    private WebView web;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // The export.pdb the page works on
    private byte[] pdbBytes;
    private Uri pdbUri;
    // Only when it was found on a stick: the stick and the folder of the file
    private Uri treeUri;
    private String rootDocId;
    private String pdbDirDocId;
    // The file to write when the page saves a copy
    private byte[] pendingSave;

    private UsbManager usb;
    private final DriveSession drive = new DriveSession();
    // What to do once the user allowed access to the stick
    private Runnable afterPermission;

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                Runnable next = afterPermission;
                afterPermission = null;
                // Asks UsbManager rather than trusting the broadcast: before
                // Android 13 other apps can send it too
                UsbDevice device = DriveSession.findStick(usb);
                if (device != null && usb.hasPermission(device) && next != null) {
                    next.run();
                } else {
                    callPage("onDrive", error("The app wasn't allowed to access the stick."));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && drive.holds(device)) {
                    io.execute(drive::release);
                    JSONObject result = new JSONObject();
                    try {
                        result.put("detached", true);
                    } catch (JSONException ignored) {
                    }
                    callPage("onDrive", result);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        // Android 15 draws the app under the system bars
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets inset = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(inset.left, inset.top, inset.right, inset.bottom);
                return insets;
            });
        }
        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.background));
        WebSettings settings = web.getSettings();
        // The page of the app and the engine are JavaScript; nothing else loads
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        // Only the page of the app runs in here, with the bridge: links go to
        // the browser
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("file".equals(url.getScheme()) && url.getPath() != null && url.getPath().startsWith("/android_asset/")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        root.addView(web);
        setContentView(root);

        usb = (UsbManager) getSystemService(USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerUsbEvents(filter);
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    // Before Android 13 a receiver can't be kept to the app; usbEvents
    // doesn't trust what it receives
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUsbEvents(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbEvents, filter);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbEvents);
        // Gives the stick back to Android
        io.execute(drive::release);
        io.shutdown();
        super.onDestroy();
    }

    // Calls a function of the page with a JSON argument
    private void callPage(String function, JSONObject arg) {
        String script = function + "(" + arg + ")";
        web.post(() -> web.evaluateJavascript(script, null));
    }

    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("error", message);
        } catch (JSONException ignored) {
        }
        return result;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private class Bridge {
        @JavascriptInterface
        public void pickStick() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_STICK);
            });
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_FILE);
            });
        }

        // The file the page was told about with onFile()
        @JavascriptInterface
        public String takeFile() {
            return pdbBytes != null ? Base64.encodeToString(pdbBytes, Base64.NO_WRAP) : "";
        }

        // Saves a copy wherever the user chooses; the page gets onSaved()
        @JavascriptInterface
        public void saveCopy(String base64) {
            pendingSave = Base64.decode(base64, Base64.NO_WRAP);
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, PDB_NAME);
                startActivityForResult(intent, SAVE_FILE);
            });
        }

        // Replaces export.pdb on the stick; the page gets onWritten()
        @JavascriptInterface
        public void writeToStick(String base64) {
            byte[] repaired = Base64.decode(base64, Base64.NO_WRAP);
            io.execute(() -> callPage("onWritten", replaceOnStick(repaired)));
        }

        // -- drive tools; the page gets onDrive() --

        // Takes the stick over from Android and reads its partition table
        @JavascriptInterface
        public void driveOpen() {
            runOnUiThread(() -> {
                UsbDevice device = DriveSession.findStick(usb);
                if (device == null) {
                    callPage("onDrive", error("No USB stick is plugged in. Plug it in (with an OTG adapter) and try again."));
                    return;
                }
                withPermission(device, () -> io.execute(() -> callPage("onDrive", driveInfo(device, null))));
            });
        }

        @JavascriptInterface
        public void driveFixType(int slot) {
            driveWrite(disk -> {
                int type = DriveTools.fixPartitionType(disk, slot);
                return String.format(Locale.US, "The type of partition %d is now 0x%02X.", slot, type);
            });
        }

        @JavascriptInterface
        public void driveFormat(String label) {
            driveWrite(disk -> {
                DriveTools.formatFat32(disk, label);
                return "The stick is now FAT32, empty.";
            });
        }

        // Gives the stick back to Android, which mounts it again
        @JavascriptInterface
        public void driveRelease() {
            io.execute(() -> {
                drive.release();
                JSONObject result = new JSONObject();
                try {
                    result.put("released", true);
                } catch (JSONException ignored) {
                }
                callPage("onDrive", result);
            });
        }

        @JavascriptInterface
        public String driveLog() {
            return DriveLog.text();
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == SAVE_FILE) {
            byte[] bytes = pendingSave;
            pendingSave = null;
            if (uri == null) {
                callPage("onSaved", error("cancelled"));
                return;
            }
            io.execute(() -> callPage("onSaved", saveTo(uri, bytes)));
            return;
        }
        if (uri == null) {
            callPage("onFile", error("cancelled"));
            return;
        }
        if (requestCode == PICK_STICK) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Works for this session without it
            }
            io.execute(() -> callPage("onFile", openStick(uri)));
        } else if (requestCode == PICK_FILE) {
            io.execute(() -> callPage("onFile", openFile(uri)));
        }
    }

    // -- drive tools ---------------------------------------------------------

    private interface DriveWork {
        /** Returns what was done, for the user */
        String run(ScsiDisk disk) throws IOException;
    }

    // Runs work once access to the stick is allowed, asking for it if needed
    private void withPermission(UsbDevice device, Runnable work) {
        if (usb.hasPermission(device)) {
            work.run();
            return;
        }
        afterPermission = work;
        // The system fills in the extras, so this has to be mutable on Android 12+
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pending = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        usb.requestPermission(device, pending);
    }

    private void driveWrite(DriveWork work) {
        runOnUiThread(() -> {
            UsbDevice device = DriveSession.findStick(usb);
            if (device == null || !drive.holds(device)) {
                callPage("onDrive", error("The stick isn't open any more. Open it again."));
                return;
            }
            web.setKeepScreenOn(true);
            io.execute(() -> {
                String done;
                try {
                    done = work.run(drive.acquire(usb, device));
                } catch (Exception e) {
                    DriveLog.log("failed: " + messageOf(e));
                    callPage("onDrive", driveInfo(device, "Failed: " + messageOf(e)));
                    web.post(() -> web.setKeepScreenOn(false));
                    return;
                }
                callPage("onDrive", driveInfo(device, done));
                web.post(() -> web.setKeepScreenOn(false));
            });
        });
    }

    // Opens the stick if needed and describes it; done is what was just
    // done to it, or why that failed (starting with "Failed")
    private JSONObject driveInfo(UsbDevice device, String done) {
        try {
            ScsiDisk disk = drive.acquire(usb, device);
            DriveInfo info = DriveInfo.inspect(disk);
            JSONObject result = new JSONObject();
            if (done != null) {
                result.put(done.startsWith("Failed") ? "failed" : "done", done);
            }
            result.put("name", disk.product.isEmpty() ? DriveSession.describe(device) : disk.product);
            result.put("bytes", info.sectorCount * info.sectorSize);
            result.put("writeProtected", disk.writeProtected);
            result.put("table", info.table.name());
            JSONArray partitions = new JSONArray();
            for (DriveInfo.Partition p : info.partitions) {
                JSONObject part = new JSONObject();
                part.put("slot", p.slot);
                part.put("type", p.type);
                part.put("typeName", p.type >= 0 ? DriveInfo.typeName(p.type) : "");
                part.put("fs", p.fs != null ? p.fs : "");
                part.put("label", p.label);
                part.put("bytes", p.sectors * info.sectorSize);
                part.put("mismatch", p.typeMismatch());
                part.put("expectedType", p.expectedType());
                partitions.put(part);
            }
            result.put("partitions", partitions);
            result.put("problems", new JSONArray(info.playerProblems()));
            return result;
        } catch (Exception e) {
            DriveLog.log("open failed: " + messageOf(e));
            drive.release();
            return error((done != null ? done + " " : "") + "Can't read the stick: " + messageOf(e)
                    + " Unplug it, plug it in again while the app is open, and retry.");
        }
    }

    // -- reading -----------------------------------------------------------

    private static class Child {
        final String docId;
        final String name;
        final boolean isDir;
        final long size;

        Child(String docId, String name, boolean isDir, long size) {
            this.docId = docId;
            this.name = name;
            this.isDir = isDir;
            this.size = size;
        }
    }

    private Child[] children(Uri tree, String parentDocId) {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
        String[] projection = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null) {
                return new Child[0];
            }
            Child[] result = new Child[cursor.getCount()];
            int i = 0;
            while (cursor.moveToNext()) {
                result[i++] = new Child(cursor.getString(0), cursor.getString(1),
                        Document.MIME_TYPE_DIR.equals(cursor.getString(2)), cursor.isNull(3) ? -1 : cursor.getLong(3));
            }
            return result;
        }
    }

    // A child by name, ignoring case like FAT does
    private Child child(Uri tree, String parentDocId, String name) {
        for (Child c : children(tree, parentDocId)) {
            if (c.name != null && c.name.equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] {Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("can't open " + uri);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    // Finds PIONEER/rekordbox/export.pdb on the stick, also when the user
    // picked the PIONEER or the rekordbox folder instead of the stick
    private JSONObject openStick(Uri tree) {
        try {
            String root = DocumentsContract.getTreeDocumentId(tree);
            String rootName = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, root));
            String dir = null;
            String path = null;
            String[][] layouts = {{"PIONEER", "rekordbox"}, {".PIONEER", "rekordbox"}, {"rekordbox"}, {}};
            for (String[] layout : layouts) {
                String docId = root;
                for (String name : layout) {
                    Child c = docId != null ? child(tree, docId, name) : null;
                    docId = c != null && c.isDir ? c.docId : null;
                }
                if (docId != null && child(tree, docId, PDB_NAME) != null) {
                    dir = docId;
                    path = layout.length > 0 ? String.join("/", layout) + "/" + PDB_NAME : PDB_NAME;
                    break;
                }
            }
            if (dir == null) {
                return error("There is no PIONEER/rekordbox/export.pdb on " + rootName
                        + ". Pick the stick itself, the one rekordbox exported to.");
            }
            Child pdb = child(tree, dir, PDB_NAME);
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, pdb.docId);
            byte[] bytes = read(uri);
            treeUri = tree;
            rootDocId = root;
            pdbDirDocId = dir;
            pdbUri = uri;
            pdbBytes = bytes;
            JSONObject result = new JSONObject();
            result.put("mode", "stick");
            result.put("name", rootName);
            result.put("path", path);
            result.put("size", bytes.length);
            // The other files of the export, to tell the user what this
            // app doesn't check
            JSONArray others = new JSONArray();
            for (Child c : children(tree, dir)) {
                if (c.name != null && !c.isDir && !c.name.equalsIgnoreCase(PDB_NAME)) {
                    others.put(c.name);
                }
            }
            result.put("others", others);
            return result;
        } catch (Exception e) {
            return error("Can't read the stick: " + messageOf(e));
        }
    }

    private JSONObject openFile(Uri uri) {
        try {
            byte[] bytes = read(uri);
            treeUri = null;
            rootDocId = null;
            pdbDirDocId = null;
            pdbUri = uri;
            pdbBytes = bytes;
            JSONObject result = new JSONObject();
            result.put("mode", "file");
            result.put("name", displayName(uri));
            result.put("size", bytes.length);
            return result;
        } catch (Exception e) {
            return error("Can't read the file: " + messageOf(e));
        }
    }

    // -- writing -----------------------------------------------------------

    private void write(Uri uri, byte[] bytes) throws IOException {
        // "wt" truncates where the provider supports it; a new file is empty anyway
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new IOException("can't write " + uri);
            }
            out.write(bytes);
            out.flush();
        }
    }

    // Writes, then reads back to make sure the stick holds what was written
    private void writeVerified(Uri uri, byte[] bytes) throws IOException {
        write(uri, bytes);
        if (!Arrays.equals(read(uri), bytes)) {
            throw new IOException("what was read back differs from what was written");
        }
    }

    private Uri createFile(String parentDocId, String name) throws IOException {
        ContentResolver resolver = getContentResolver();
        Child existing = child(treeUri, parentDocId, name);
        if (existing != null) {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId));
        }
        Uri uri = DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId), "application/octet-stream", name);
        if (uri == null) {
            throw new IOException("can't create " + name);
        }
        // Providers may append an extension or a number
        if (!name.equals(displayName(uri))) {
            Uri renamed = DocumentsContract.renameDocument(resolver, uri, name);
            uri = renamed != null ? renamed : uri;
        }
        return uri;
    }

    private String createDir(String parentDocId, String name) throws IOException {
        Child existing = child(treeUri, parentDocId, name);
        if (existing != null && existing.isDir) {
            return existing.docId;
        }
        Uri uri = DocumentsContract.createDocument(getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId), Document.MIME_TYPE_DIR, name);
        if (uri == null) {
            throw new IOException("can't create the folder " + name);
        }
        return DocumentsContract.getDocumentId(uri);
    }

    private static Uri renamed(Uri result, Uri original) {
        return result != null ? result : original;
    }

    // Replaces export.pdb on the stick with the repaired file, without a
    // moment where neither is there:
    // 1. the original goes to the storage of the app and to a folder on the
    //    stick outside PIONEER, both read back;
    // 2. the repaired file is written beside it as export.pdb.new, read back;
    // 3. export.pdb is renamed to export.pdb.old, export.pdb.new to
    //    export.pdb, and export.pdb.old deleted. A failed rename puts the
    //    original back.
    private JSONObject replaceOnStick(byte[] repaired) {
        if (treeUri == null || pdbBytes == null) {
            return error("No stick is open.");
        }
        ContentResolver resolver = getContentResolver();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date());
        JSONObject result = new JSONObject();
        try {
            // 1. Backups
            File appBackupDir = new File(getExternalFilesDir(null), "backups/" + stamp);
            if (!appBackupDir.mkdirs() && !appBackupDir.isDirectory()) {
                return error("Can't create the backup folder of the app.");
            }
            File appBackup = new File(appBackupDir, PDB_NAME);
            try (FileOutputStream out = new FileOutputStream(appBackup)) {
                out.write(pdbBytes);
            }
            String backupsDir = createDir(rootDocId, BACKUP_DIR_NAME);
            String stampDir = createDir(backupsDir, stamp);
            writeVerified(createFile(stampDir, PDB_NAME), pdbBytes);
            result.put("backupOnStick", BACKUP_DIR_NAME + "/" + stamp + "/" + PDB_NAME);
            result.put("backupInApp", appBackup.getAbsolutePath());

            // 2. The repaired file
            Uri fresh = createFile(pdbDirDocId, PDB_NAME + ".new");
            try {
                writeVerified(fresh, repaired);
            } catch (IOException e) {
                DocumentsContract.deleteDocument(resolver, fresh);
                throw e;
            }

            // 3. The swap
            Child staleOld = child(treeUri, pdbDirDocId, PDB_NAME + ".old");
            if (staleOld != null) {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, staleOld.docId));
            }
            Uri old;
            try {
                old = renamed(DocumentsContract.renameDocument(resolver, pdbUri, PDB_NAME + ".old"), pdbUri);
            } catch (Exception e) {
                DocumentsContract.deleteDocument(resolver, fresh);
                throw new IOException("can't rename export.pdb, the stick is unchanged: " + messageOf(e));
            }
            Uri replaced;
            try {
                replaced = renamed(DocumentsContract.renameDocument(resolver, fresh, PDB_NAME), fresh);
            } catch (Exception e) {
                DocumentsContract.renameDocument(resolver, old, PDB_NAME);
                DocumentsContract.deleteDocument(resolver, fresh);
                throw new IOException("can't rename export.pdb.new, the original is back: " + messageOf(e));
            }
            DocumentsContract.deleteDocument(resolver, old);

            // What the stick holds now, for the page to check again
            pdbUri = replaced;
            pdbBytes = read(replaced);
            result.put("size", pdbBytes.length);
            result.put("ok", true);
            return result;
        } catch (Exception e) {
            try {
                result.put("error", "The repaired file wasn't written: " + messageOf(e));
            } catch (JSONException ignored) {
            }
            return result;
        }
    }

    private JSONObject saveTo(Uri uri, byte[] bytes) {
        try {
            write(uri, bytes);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("name", displayName(uri));
            return result;
        } catch (Exception e) {
            return error("Can't save the file: " + messageOf(e));
        }
    }
}
