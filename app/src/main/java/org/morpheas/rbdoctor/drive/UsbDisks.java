// Ported from usbformat (https://github.com/blackorange-sketch/usbformat),
// MIT licensed, see third_party/usbformat/LICENSE.
package org.morpheas.rbdoctor.drive;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;

/** Opens USB sticks for raw sector access through the USB host API, no root needed. */
public final class UsbDisks {
    private UsbDisks() {
    }

    private static class AndroidUsbTransport implements UsbTransport {
        private final UsbDeviceConnection connection;
        private final UsbInterface usbInterface;
        private final UsbEndpoint inEndpoint;
        private final UsbEndpoint outEndpoint;
        private final String openLog;
        private int lastOut;
        private int lastIn;
        private int lastControl;

        AndroidUsbTransport(UsbDeviceConnection connection, UsbInterface usbInterface, UsbEndpoint inEndpoint,
                UsbEndpoint outEndpoint, String openLog) {
            this.connection = connection;
            this.usbInterface = usbInterface;
            this.inEndpoint = inEndpoint;
            this.outEndpoint = outEndpoint;
            this.openLog = openLog;
        }

        @Override
        public int bulkOut(byte[] data, int offset, int length, int timeoutMs) {
            lastOut = connection.bulkTransfer(outEndpoint, data, offset, length, timeoutMs);
            return lastOut;
        }

        @Override
        public int bulkIn(byte[] buffer, int offset, int length, int timeoutMs) {
            lastIn = connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs);
            return lastIn;
        }

        @Override
        public String describe() {
            return openLog + "; out=" + lastOut + " in=" + lastIn + " ctl=" + lastControl;
        }

        // CLEAR_FEATURE(ENDPOINT_HALT) on one endpoint of the drive. It
        // doesn't touch the host's state: SET_INTERFACE would, but it resets
        // both host endpoints, so calling it after clearing only one of them
        // left the other out of step. resync() resets both sides.
        @Override
        public void clearHalt(boolean in) {
            clearHaltOnDrive(in ? inEndpoint : outEndpoint);
        }

        @Override
        public void resync() {
            connection.setInterface(usbInterface);
            clearHaltOnDrive(inEndpoint);
            clearHaltOnDrive(outEndpoint);
        }

        private void clearHaltOnDrive(UsbEndpoint endpoint) {
            lastControl = connection.controlTransfer(0x02, 0x01, 0, endpoint.getAddress(), null, 0, 1000);
        }

        @Override
        public void reset() {
            // Bulk-Only Mass Storage Reset, class request 0xFF to the interface
            lastControl = connection.controlTransfer(0x21, 0xFF, 0, usbInterface.getId(), null, 0, 1000);
            clearHaltOnDrive(inEndpoint);
            clearHaltOnDrive(outEndpoint);
        }

        @Override
        public int endpointStatus(boolean in) {
            UsbEndpoint endpoint = in ? inEndpoint : outEndpoint;
            byte[] buffer = new byte[2];
            int n = connection.controlTransfer(0x82, 0x00, 0, endpoint.getAddress(), buffer, 2, 1000);
            return n == 2 ? buffer[0] & 0xFF : -1;
        }

        // UsbDeviceConnection.resetDevice() is hidden API, looked up by name
        @Override
        public boolean hardReset() {
            boolean reset;
            try {
                Object result = connection.getClass().getMethod("resetDevice").invoke(connection);
                reset = Boolean.TRUE.equals(result);
            } catch (Exception e) {
                reset = false;
            }
            if (!reset) {
                return false;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            boolean claimed = connection.claimInterface(usbInterface, true);
            connection.setInterface(usbInterface);
            return claimed;
        }

        @Override
        public void close() {
            connection.releaseInterface(usbInterface);
            connection.close();
        }
    }

    /** The Bulk-Only mass storage interface of a device, or null when it has none. */
    public static UsbInterface massStorageInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            // Bulk-Only Transport; UAS-capable drives list it as a separate alternate setting
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                    && candidate.getInterfaceProtocol() == 0x50) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Opens a USB stick for raw sector access; USB permission must already
     * be granted. Claiming the interface makes Android unmount the stick
     * until it is closed again.
     */
    public static ScsiDisk open(UsbManager manager, UsbDevice device, int ioTimeoutMs) throws IOException {
        UsbInterface usbInterface = massStorageInterface(device);
        if (usbInterface == null) {
            throw new IOException("This USB device is not a USB stick or drive (no Bulk-Only mass storage interface)");
        }
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                in = endpoint;
            } else {
                out = endpoint;
            }
        }
        if (in == null || out == null) {
            throw new IOException("The USB interface has no bulk endpoints");
        }
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Can't open the USB device (no permission?)");
        }
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            throw new IOException("Can't take over the USB stick from Android");
        }
        // The Bulk-Only alternate setting (drives that also speak UAS may be in another)
        boolean setInterfaceOk = connection.setInterface(usbInterface);
        // Get Max LUN: drives with a single LUN may stall this, which is fine
        byte[] lunBuffer = new byte[1];
        int lunResult = connection.controlTransfer(0xA1, 0xFE, 0, usbInterface.getId(), lunBuffer, 1, 1000);

        StringBuilder interfaces = new StringBuilder();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface u = device.getInterface(i);
            if (i > 0) {
                interfaces.append(',');
            }
            interfaces.append(u.getId()).append('/').append(u.getAlternateSetting()).append(':')
                    .append(u.getInterfaceClass()).append(':').append(u.getInterfaceSubclass()).append(':')
                    .append(u.getInterfaceProtocol());
        }
        String openLog = String.format(Locale.US, "interfaces=%s using %d/%d, out=0x%02x/%d in=0x%02x/%d, setIf=%b maxLun=%d/%d",
                interfaces, usbInterface.getId(), usbInterface.getAlternateSetting(), out.getAddress(), out.getMaxPacketSize(),
                in.getAddress(), in.getMaxPacketSize(), setInterfaceOk, lunResult, lunBuffer[0]);
        DriveLog.log("usb: " + openLog);
        AndroidUsbTransport transport = new AndroidUsbTransport(connection, usbInterface, in, out, openLog);
        try {
            // SET_INTERFACE above reset the host's endpoint state; make the drive's match
            transport.clearHalt(true);
            transport.clearHalt(false);
            return new ScsiDisk(transport, ioTimeoutMs);
        } catch (IOException | RuntimeException e) {
            transport.close();
            throw e;
        }
    }
}
