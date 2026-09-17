package com.ripostelabs.news;

import java.util.ArrayList;
import java.util.List;

/** scope/run-tests.sh runs main(); a wrong answer throws. */
public final class FeedMergeTest {
    public static void main(String[] args) {
        sameLinkTwice();
        linkVariantsAreOneStory();
        noLinkFallsBackToTitle();
        differentStoriesStay();
        System.out.println("FeedMergeTest: ok");
    }

    private static Item item(String title, String link, long time) {
        Item it = new Item();
        it.title = title;
        it.link = link;
        it.source = "BBC News";
        it.time = time;
        return it;
    }

    private static void sameLinkTwice() {
        List<Item> in = new ArrayList<>();
        in.add(item("Crocodiles", "https://bbc.co.uk/news/1", 20));
        in.add(item("Crocodiles", "https://bbc.co.uk/news/1", 10));
        List<Item> out = FeedMerge.dedupe(in);
        check(out.size() == 1, "same link twice -> one item, got " + out.size());
        check(out.get(0).time == 20, "the first copy is the one kept");
    }

    private static void linkVariantsAreOneStory() {
        List<Item> in = new ArrayList<>();
        in.add(item("A", "https://bbc.co.uk/news/1", 0));
        in.add(item("A", "https://bbc.co.uk/news/1/?at_medium=RSS", 0));
        in.add(item("A", "HTTPS://BBC.co.uk/news/1#top", 0));
        check(FeedMerge.dedupe(in).size() == 1, "query, hash, slash and case are not new stories");
    }

    private static void noLinkFallsBackToTitle() {
        List<Item> in = new ArrayList<>();
        in.add(item("Crocodiles  in the river", null, 0));
        in.add(item("crocodiles in the river ", "", 0));
        check(FeedMerge.dedupe(in).size() == 1, "no link: the title, normalised, is the key");
    }

    private static void differentStoriesStay() {
        List<Item> in = new ArrayList<>();
        in.add(item("A", "https://bbc.co.uk/news/1", 0));
        in.add(item("B", "https://bbc.co.uk/news/2", 0));
        in.add(item("A", null, 0));   // same title, but a link-less item is judged by title alone
        check(FeedMerge.dedupe(in).size() == 3, "distinct links and a title-only item all stay");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
