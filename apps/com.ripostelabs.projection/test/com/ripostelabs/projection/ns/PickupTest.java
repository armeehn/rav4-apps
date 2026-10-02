package com.ripostelabs.projection.ns;

/**
 * Which capture the CarPlay mic uses. Calls sounded "underwater" through the vendor HAL's
 * voice-communication processing, on stock firmware too, so the default is the plain mic.
 */
public final class PickupTest {

    public static void main(String[] args) {
        defaultIsThePlainMic();
        storedNamesComeBack();
        onlyPlatformAsksForVendorProcessing();
        System.out.println("ok   PickupTest " + Check.count + " checks");
    }

    private static void defaultIsThePlainMic() {
        Check.that(Pickup.parse(null) == Pickup.DIRECT, "nothing stored");
        Check.that(Pickup.parse("SOMETHING_OLD") == Pickup.DIRECT, "unknown name");
    }

    private static void storedNamesComeBack() {
        for (Pickup p : Pickup.values()) {
            Check.that(Pickup.parse(p.name()) == p, p.name() + " round trip");
        }
    }

    private static void onlyPlatformAsksForVendorProcessing() {
        Check.that(Pickup.PLATFORM.vendorProcessing(), "platform runs the HAL's AEC and NS");
        Check.that(!Pickup.DIRECT.vendorProcessing(), "direct is the plain mic");
    }
}
