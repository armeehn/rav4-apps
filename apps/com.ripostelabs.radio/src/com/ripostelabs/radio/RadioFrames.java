package com.ripostelabs.radio;

/**
 * MCU frame bodies the radio sends, byte for byte as the vendor app builds them. Pure, so
 * the bytes have a test.
 *
 *   02 k         radio key k (sendRadioKey; the gateway builds this one itself)
 *   02 64 slot   recall preset slot (sendRadioCmd(100), FreqView.java:176-181)
 *   02 65 slot   store the current station in slot (sendRadioCmd(101), FreqView.java:161-167)
 */
final class RadioFrames {

    private static final byte OP_RADIO_KEY = 0x02;
    private static final int CMD_PRESET_SELECT = 100;
    private static final int CMD_PRESET_STORE = 101;

    private RadioFrames() {}

    static byte[] key(int key) {
        return new byte[]{OP_RADIO_KEY, (byte) key};
    }

    static byte[] recall(int slot) {
        return cmd(CMD_PRESET_SELECT, slot);
    }

    static byte[] store(int slot) {
        return cmd(CMD_PRESET_STORE, slot);
    }

    private static byte[] cmd(int cmd, int arg) {
        return new byte[]{OP_RADIO_KEY, (byte) cmd, (byte) (arg & 0xFF)};
    }
}
