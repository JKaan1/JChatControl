package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.core.ai.ResponseParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserTest {

    private static final Set<String> CATS = Set.of("profanity", "insult", "racism", "politics");

    @Test
    void parsesBatchJsonInFences() {
        ResponseParser p = new ResponseParser(ResponseParser.Mode.JSON, CATS, "profanity", Map.of(), List.of());
        String answer = "```json\n{\"r\":[{\"i\":1,\"f\":true,\"c\":[\"Insult\"],\"w\":[\"salak\"],\"s\":0.9},"
                + "{\"i\":0,\"f\":false,\"c\":[],\"w\":[],\"s\":0.02}]}\n```";
        List<AiResult> r = p.parse(answer, 3, "x");
        assertFalse(r.get(0).flagged());
        assertTrue(r.get(1).flagged());
        assertEquals(List.of("insult"), r.get(1).categories());
        assertEquals(List.of("salak"), r.get(1).words());
        assertNull(r.get(2), "missing answer stays null");
    }

    @Test
    void unknownCategoryUsesDefault() {
        ResponseParser p = new ResponseParser(ResponseParser.Mode.JSON, CATS, "profanity", Map.of(), List.of());
        AiResult r = p.parse("{\"r\":[{\"i\":0,\"f\":true,\"c\":[\"toxicity\"],\"s\":80}]}", 1, "x").get(0);
        assertEquals(List.of("profanity"), r.categories());
        assertEquals(0.8, r.score(), 1e-9);
    }

    @Test
    void parsesGuardModels() {
        ResponseParser p = new ResponseParser(ResponseParser.Mode.GUARD, CATS, "insult",
                Map.of("S10", "racism", "S2", "ignore"), List.of());
        assertFalse(p.parse("safe", 1, "g").get(0).flagged());
        AiResult r = p.parse("unsafe\nS10", 1, "g").get(0);
        assertTrue(r.flagged());
        assertEquals(List.of("racism"), r.categories());
        assertFalse(p.parse("unsafe\nS2", 1, "g").get(0).flagged(), "ignored category");
    }

    @Test
    void parsesYesNoAndLabels() {
        ResponseParser yn = new ResponseParser(ResponseParser.Mode.YES_NO, CATS, "insult", Map.of(), List.of());
        assertTrue(yn.parse("Yes", 1, "y").get(0).flagged());
        assertFalse(yn.parse("No", 1, "y").get(0).flagged());
        ResponseParser label = new ResponseParser(ResponseParser.Mode.LABEL, CATS, "profanity",
                Map.of("hate", "racism"), List.of("toxic", "hate"));
        assertEquals(List.of("racism"), label.parse("label: HATE", 1, "l").get(0).categories());
        assertFalse(label.parse("clean", 1, "l").get(0).flagged());
    }
}
