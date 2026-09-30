package com.ripostelabs.weather;

/**
 * The launcher's home card reads what this app caches. A wrong column order shows the high as
 * the temperature; a wrong URL fetches Celsius for a driver who asked for Fahrenheit.
 */
public final class WeatherLogicTest {

    public static void main(String[] args) {
        // The unit: stored by name, Celsius when unknown, the button flips it.
        expect(WeatherLogic.Unit.C, WeatherLogic.Unit.parse(null));
        expect(WeatherLogic.Unit.C, WeatherLogic.Unit.parse("K"));
        expect(WeatherLogic.Unit.F, WeatherLogic.Unit.parse("F"));
        expect(WeatherLogic.Unit.F, WeatherLogic.Unit.C.toggle());
        expect(WeatherLogic.Unit.C, WeatherLogic.Unit.F.toggle());

        // Open-Meteo converts server-side; Celsius is its default and needs no parameter.
        String f = WeatherLogic.forecastUrl(49.88, -119.49, WeatherLogic.Unit.F);
        check(f.contains("temperature_unit=fahrenheit"), f);
        check(f.contains("latitude=49.88") && f.contains("longitude=-119.49"), f);
        String c = WeatherLogic.forecastUrl(49.88, -119.49, WeatherLogic.Unit.C);
        check(!c.contains("temperature_unit"), c);

        String aq = WeatherLogic.airUrl(49.88, -119.49);
        check(aq.startsWith("https://air-quality-api.open-meteo.com/v1/air-quality?"), aq);
        check(aq.contains("current=us_aqi"), aq);

        // US AQI bands (EPA): the words a driver acts on in wildfire season.
        expect("Good", WeatherLogic.aqiBand(0));
        expect("Good", WeatherLogic.aqiBand(50));
        expect("Moderate", WeatherLogic.aqiBand(51));
        expect("Unhealthy for some", WeatherLogic.aqiBand(101));
        expect("Unhealthy", WeatherLogic.aqiBand(151));
        expect("Very unhealthy", WeatherLogic.aqiBand(201));
        expect("Hazardous", WeatherLogic.aqiBand(301));

        // The provider row lines up with its columns, nulls where a value is missing.
        WeatherLogic.Reading r = new WeatherLogic.Reading(
                14.5, 18.0, 6.0, WeatherLogic.Unit.C, 3, 42, "Kelowna", 1_790_000_000_000L);
        Object[] row = r.row();
        expect(WeatherLogic.COLUMNS.length, row.length);
        expect(14.5, row[index("temp")]);
        expect(18.0, row[index("high")]);
        expect(6.0, row[index("low")]);
        expect("C", row[index("unit")]);
        expect(3, row[index("code")]);
        expect(42, row[index("aqi")]);
        expect("Kelowna", row[index("place")]);
        expect(1_790_000_000_000L, row[index("updated")]);

        WeatherLogic.Reading bare = new WeatherLogic.Reading(
                60.0, null, null, WeatherLogic.Unit.F, 0, null, "", 1L);
        expect(null, bare.row()[index("aqi")]);
        expect(null, bare.row()[index("high")]);

        // The cache survives a restart as one line, and a damaged line reads as nothing.
        WeatherLogic.Reading back = WeatherLogic.Reading.decode(r.encode());
        expect(r.encode(), back.encode());
        expect(null, WeatherLogic.Reading.decode("garbage"));
        expect(null, WeatherLogic.Reading.decode(null));
        expect(bare.encode(), WeatherLogic.Reading.decode(bare.encode()).encode());

        System.out.println("WeatherLogicTest: ok");
    }

    private static int index(String col) {
        for (int i = 0; i < WeatherLogic.COLUMNS.length; i++) {
            if (WeatherLogic.COLUMNS[i].equals(col)) {
                return i;
            }
        }
        throw new AssertionError("no column " + col);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void expect(Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError("expected " + want + " got " + got);
        }
    }
}
