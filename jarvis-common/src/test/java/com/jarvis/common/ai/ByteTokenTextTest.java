package com.jarvis.common.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ByteTokenTextTest {

    @Test
    void turnsByteTokensBackIntoCharacters() {
        assertEquals("<div class=\"icon\">🛞</div>", ByteTokenText.repair("<div class=\"icon\"><0xF0><0x9F><0x9B><0x9E></div>"));
        assertEquals("Zażółć", ByteTokenText.repair("Za<0xC5><0xBC><0xC3><0xB3><0xC5><0x82>ć"));
    }

    @Test
    void leavesInvalidSequencesAndNormalTextAlone() {
        String broken = "half <0xF0><0x9F> emoji";
        assertSame(broken, ByteTokenText.repair(broken));
        String plain = "<a href=\"#x\">0x10</a>";
        assertSame(plain, ByteTokenText.repair(plain));
    }

    @Test
    void repairsNestedToolArguments() {
        Map<String, Object> arguments = Map.of("path", "index.html",
                "edits", List.of(Map.of("expected", "x", "replacement", "<0xE2><0x98><0x85> 5/5")));
        Map<String, Object> repaired = ByteTokenText.repairArguments(arguments);
        assertEquals("★ 5/5", ((Map<?, ?>) ((List<?>) repaired.get("edits")).get(0)).get("replacement"));
        Map<String, Object> untouched = Map.of("path", "a.txt");
        assertSame(untouched, ByteTokenText.repairArguments(untouched));
    }
}
