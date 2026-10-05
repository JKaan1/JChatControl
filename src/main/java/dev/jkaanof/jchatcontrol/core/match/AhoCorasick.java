package dev.jkaanof.jchatcontrol.core.match;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Immutable Aho-Corasick automaton: finds every "contains" word inside a text in a single O(n) pass,
 * regardless of how many words are in the list. Used for substring style bad-word matching.
 */
public final class AhoCorasick {

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>(4);
        Node fail;
        String word;      // word ending exactly here
        Node outLink;     // next node (via fail links) that ends a word
    }

    private final Node root = new Node();
    private final int size;

    public AhoCorasick(Iterable<String> words) {
        int count = 0;
        for (String w : words) {
            if (w == null || w.isEmpty()) {
                continue;
            }
            Node n = root;
            for (int i = 0; i < w.length(); i++) {
                n = n.next.computeIfAbsent(w.charAt(i), k -> new Node());
            }
            if (n.word == null) {
                n.word = w;
                count++;
            }
        }
        this.size = count;
        build();
    }

    private void build() {
        Queue<Node> queue = new ArrayDeque<>();
        root.fail = root;
        for (Node child : root.next.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            for (Map.Entry<Character, Node> e : n.next.entrySet()) {
                char c = e.getKey();
                Node child = e.getValue();
                Node f = n.fail;
                while (f != root && !f.next.containsKey(c)) {
                    f = f.fail;
                }
                Node target = f.next.get(c);
                child.fail = (target != null && target != child) ? target : root;
                child.outLink = child.fail.word != null ? child.fail : child.fail.outLink;
                queue.add(child);
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** First (shortest-ending) word contained in text, or null. */
    public String findFirst(CharSequence text) {
        if (size == 0) {
            return null;
        }
        Node n = root;
        for (int i = 0; i < text.length(); i++) {
            n = step(n, text.charAt(i));
            if (n.word != null) {
                return n.word;
            }
            if (n.outLink != null) {
                return n.outLink.word;
            }
        }
        return null;
    }

    /** Every word contained in text (may contain duplicates if a word occurs twice). */
    public List<String> findAll(CharSequence text) {
        List<String> out = new ArrayList<>(0);
        if (size == 0) {
            return out;
        }
        Node n = root;
        for (int i = 0; i < text.length(); i++) {
            n = step(n, text.charAt(i));
            Node o = n.word != null ? n : n.outLink;
            while (o != null) {
                out.add(o.word);
                o = o.outLink;
            }
        }
        return out;
    }

    private Node step(Node n, char c) {
        while (n != root && !n.next.containsKey(c)) {
            n = n.fail;
        }
        Node next = n.next.get(c);
        return next == null ? root : next;
    }
}
