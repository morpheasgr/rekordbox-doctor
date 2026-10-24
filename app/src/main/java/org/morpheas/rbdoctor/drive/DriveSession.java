// After the UsbSession of usbformat (https://github.com/blackorange-sketch/usbformat),
// MIT licensed, see third_party/usbformat/LICENSE.
package org.morpheas.rbdoctor.drive;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * The stick the drive tools hold, from opening it until it is given back.
 * Every close hands the stick back to Android, which mounts it and starts
 * reading it; taking it over again a moment later interrupts that and can
 * leave the stick unresponsive. So it stays open for inspecting, fixing and
 * inspecting again, until release() or until it is unplugged.
 */
public final class DriveSession {
    private ScsiDisk disk;
    private String deviceName;
    private Thread keepAlive;

    /** The first USB stick plugged in, null when none */
    public static UsbDevice findStick(UsbManager manager) {
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (UsbDisks.massStorageInterface(device) != null) {
                return device;
            }
        }
        return null;
    }

    public static String describe(UsbDevice device) {
        String name = "";
        try {
            String maker = device.getManufacturerName();
            String product = device.getProductName();
            name = ((maker != null ? maker : "") + " " + (product != null ? product : "")).trim();
        } catch (SecurityException ignored) {
            // Readable only with permission on some versions
        }
        return name.isEmpty() ? "USB stick" : name;
    }

    /** The open stick, opening it first when needed. Blocks; call from a worker thread. */
    public synchronized ScsiDisk acquire(UsbManager manager, UsbDevice device) throws IOException {
        if (disk != null && device.getDeviceName().equals(deviceName)) {
            return disk;
        }
        release();
        disk = UsbDisks.open(manager, device, 30_000);
        deviceName = device.getDeviceName();
        DriveLog.log("session: stick taken over from Android");
        startKeepAlive(disk);
        return disk;
    }

    public synchronized boolean isOpen() {
        return disk != null;
    }

    public synchronized boolean holds(UsbDevice device) {
        return disk != null && device.getDeviceName().equals(deviceName);
    }

    // A harmless TEST UNIT READY every 1.5 s. Some sticks fail their first
    // transfer after the link was idle for a few seconds (power saving on the
    // link or in the stick); regular traffic prevents that.
    private void startKeepAlive(ScsiDisk target) {
        keepAlive = new Thread(() -> {
            int failures = 0;
            while (true) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (this) {
                    if (disk != target) {
                        return;
                    }
                }
                if (target.ping()) {
                    failures = 0;
                } else if (++failures >= 2) {
                    DriveLog.log("session: keep-alive got no answer twice; stopping it");
                    return;
                }
            }
        }, "usb-keepalive");
        keepAlive.setDaemon(true);
        keepAlive.start();
    }

    /** Gives the stick back to Android, which mounts it again. */
    public synchronized void release() {
        if (disk == null) {
            return;
        }
        if (keepAlive != null) {
            keepAlive.interrupt();
            keepAlive = null;
        }
        try {
            disk.close();
        } catch (RuntimeException e) {
            DriveLog.log("session: error while closing: " + e.getMessage());
        }
        disk = null;
        deviceName = null;
        DriveLog.log("session: stick given back to Android");
    }
}
