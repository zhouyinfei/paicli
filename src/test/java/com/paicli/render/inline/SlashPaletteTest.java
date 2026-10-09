package com.paicli.render.inline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashPaletteTest {

    @Test
    void enterConfirmsCurrentSelection() {
        int decision = SlashPalette.handleKey('\r', 2, 5);
        assertEquals(-2, decision);  // DECISION_CONFIRM（中文：确认决定。）
    }

    @Test
    void escCancelsPalette() {
        int decision = SlashPalette.handleKey(-2, 0, 5);
        assertEquals(-1, decision);  // DECISION_CANCEL（中文：取消决定。）
    }

    @Test
    void numberKeyJumpsToIndex() {
        int decision = SlashPalette.handleKey('3', 0, 5);
        assertEquals(2, decision);  // index 2 (3rd item)（中文：索引 2，即第三项。）
    }

    @Test
    void numberKeyOutOfRangeFallsThrough() {
        int decision = SlashPalette.handleKey('9', 0, 3);
        // 9 - '1' = 8, 8 >= 3 → not direct selection, falls through to default（中文：计算得到 8，超出 3 项范围，因此不直接选择而进入默认分支。）
        assertTrue(decision != 8);
    }

    @Test
    void upArrowMovesSelection() {
        int decision = SlashPalette.handleKey(-3, 0, 5);
        assertEquals(-3, decision);  // DECISION_UP（中文：向上移动的决定。）
    }

    @Test
    void downArrowMovesSelection() {
        int decision = SlashPalette.handleKey(-4, 0, 5);
        assertEquals(-4, decision);
    }

    @Test
    void vimKeyKMovesUp() {
        int decision = SlashPalette.handleKey('k', 1, 5);
        assertEquals(-3, decision);
    }

    @Test
    void vimKeyJMovesDown() {
        int decision = SlashPalette.handleKey('j', 1, 5);
        assertEquals(-4, decision);
    }

    @Test
    void qExitsPalette() {
        int decision = SlashPalette.handleKey('q', 0, 5);
        assertEquals(-1, decision);
    }
}
