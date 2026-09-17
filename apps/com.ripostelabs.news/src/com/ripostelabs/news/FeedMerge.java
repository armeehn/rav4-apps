package com.ripostelabs.news;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure list logic for the feed views; nothing here touches Android.
 *
 * Feeds repeat themselves: BBC's front-page RSS carried the same story twice on
 * 2026-09-17 (once per section it was filed under), and Top Stories merges five
 * feeds that syndicate each other. The reader sees one card per story.
 */
final class FeedMerge {
    private FeedMerge() {}

    /** The list with later repeats dropped; order and the first copy are kept. */
    static List<Item> dedupe(List<Item> in) {
        Set<String> seen = new HashSet<>();
        List<Item> out = new ArrayList<>(in.size());
        for (Item it : in) {
            if (seen.add(key(it))) out.add(it);
        }
        return out;
    }

    /**
     * What makes two items the same story: the link when there is one, else the title.
     * Both are normalised, because a tracking query string or a trailing slash is not a
     * different story, and neither is a title with different spacing or case.
     */
    static String key(Item it) {
        String link = it.link == null ? "" : it.link.trim();
        if (!link.isEmpty()) {
            int q = link.indexOf('?');
            if (q >= 0) link = link.substring(0, q);
            int h = link.indexOf('#');
            if (h >= 0) link = link.substring(0, h);
            while (link.endsWith("/")) link = link.substring(0, link.length() - 1);
            return "l:" + link.toLowerCase();
        }
        String title = it.title == null ? "" : it.title;
        return "t:" + title.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
