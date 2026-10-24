// Ported from usbformat (https://github.com/blackorange-sketch/usbformat),
// MIT licensed, see third_party/usbformat/LICENSE.
package org.morpheas.rbdoctor.drive;

/** The few USB operations SCSI over Bulk-Only needs. On Android: UsbDisks. */
public interface UsbTransport {
    /** The number of bytes moved, or a negative value on error, timeout or stall. */
    int bulkOut(byte[] data, int offset, int length, int timeoutMs);

    int bulkIn(byte[] buffer, int offset, int length, int timeoutMs);

    /** Clears a halted (stalled) bulk endpoint on the drive. */
    void clearHalt(boolean inEndpoint);

    /** Bulk-Only Mass Storage Reset, then clears both endpoints. */
    void reset();

    /**
     * Puts the host's and the drive's view of both bulk endpoints back in
     * step: SET_INTERFACE resets the host side, clearing the halt on both
     * endpoints the drive side. Doing only one half leaves them out of step.
     */
    default void resync() {
    }

    void close();

    /** A short technical description of the connection, for error messages. */
    default String describe() {
        return "";
    }

    /** Resets the whole device through its port, like replugging it. False when that isn't possible. */
    default boolean hardReset() {
        return false;
    }

    /** GET_STATUS of a bulk endpoint: bit 0 set means halted. -1 when unavailable. */
    default int endpointStatus(boolean inEndpoint) {
        return -1;
    }
}
