package dev.jkaanof.jchatcontrol.core.match;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable prefix trie with an attached value per word. Lookups never allocate.
 * Used for "prefix" bad words (Turkish suffixes: "salak" -> "salaksın"), suspicious words
 * and allowed-word stem matching ("oyun" -> "oyunlarımız").
 */
public final class PrefixTrie<V> {

    private static final class Node<V> {
        final Map<Character, Node<V>> next = new HashMap<>(4);
        String word;
        V value;
    }

    private final Node<V> root = new Node<>();
    private int size;

    public PrefixTrie() {
    }

    public PrefixTrie(Map<String, V> words) {
        words.forEach(this::put);
    }

    /** Only call while building (not thread-safe). */
    public void put(String word, V value) {
        if (word == null || word.isEmpty()) {
            return;
        }
        Node<V> n = root;
        for (int i = 0; i < word.length(); i++) {
            n = n.next.computeIfAbsent(word.charAt(i), k -> new Node<>());
        }
        if (n.word == null) {
            size++;
        }
        n.word = word;
        n.value = value;
    }

    public int size() {
        return size;
    }

    /** Shortest word in the trie that is a prefix of text, or null. */
    public String shortestPrefixOf(CharSequence text) {
        Node<V> n = root;
        for (int i = 0; i < text.length(); i++) {
            n = n.next.get(text.charAt(i));
            if (n == null) {
                return null;
            }
            if (n.word != null) {
                return n.word;
            }
        }
        return null;
    }

    /** Length of the longest word that is a prefix of text, or 0. */
    public int longestPrefixLength(CharSequence text) {
        Node<V> n = root;
        int best = 0;
        for (int i = 0; i < text.length(); i++) {
            n = n.next.get(text.charAt(i));
            if (n == null) {
                break;
            }
            if (n.word != null) {
                best = i + 1;
            }
        }
        return best;
    }

    public V get(String word) {
        Node<V> n = root;
        for (int i = 0; i < word.length(); i++) {
            n = n.next.get(word.charAt(i));
            if (n == null) {
                return null;
            }
        }
        return n.word != null ? n.value : null;
    }
}
