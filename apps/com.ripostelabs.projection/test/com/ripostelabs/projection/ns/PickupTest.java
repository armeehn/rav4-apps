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
        theTestCoversEveryRoute();
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
        Check.that(!Pickup.RECOGNITION.vendorProcessing(), "recognition has no HAL ECNS");
        Check.that(!Pickup.UNPROCESSED.vendorProcessing(), "unprocessed has no HAL ECNS");
    }

    // The mic source test walks values() in order: the plain mic first, the vendor path last.
    private static void theTestCoversEveryRoute() {
        Check.eq(4, Pickup.values().length, "routes");
        Check.that(Pickup.values()[0] == Pickup.DIRECT, "plain mic first");
        Check.that(Pickup.values()[Pickup.values().length - 1] == Pickup.PLATFORM, "vendor path last");
    }
}
