package com.ripostelabs.news;

/** One headline. Its own file so the Android-free logic (and its test) can see it. */
final class Item {
    String title, link, source, snippet;
    long time;     // epoch millis, 0 if unknown
}
