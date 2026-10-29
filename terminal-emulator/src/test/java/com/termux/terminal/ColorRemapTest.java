package com.termux.terminal;

import junit.framework.TestCase;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Fork addition: `remap=teal-red` (scope.md 2026-10-29). */
public class ColorRemapTest extends TestCase {

    private static float hue(int argb) {
        float r = ((argb >> 16) & 0xff) / 255f, g = ((argb >> 8) & 0xff) / 255f, b = (argb & 0xff) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d < 1e-6f) return -1;
        float h = max == r ? ((g - b) / d) % 6f : (max == g ? (b - r) / d + 2f : (r - g) / d + 4f);
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }

    private static boolean nearRed(float h) {
        return h >= 300f || h <= 15f;
    }

    public void testRemapForcesTealAndDistinctReds() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        int[] stock = scheme.mDefaultColors.clone();
        Properties props = new Properties();
        props.put("remap", "teal-red");
        scheme.updateWith(props);

        assertEquals("black stays black", stock[0], scheme.mDefaultColors[0]);
        for (int grey : new int[]{7, 8, 15}) {
            float h = hue(scheme.mDefaultColors[grey]);
            assertTrue("grey " + grey + " -> teal, hue " + h, h > 150f && h < 190f);
        }
        Set<Integer> reds = new HashSet<>();
        for (int i : new int[]{1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14}) {
            int c = scheme.mDefaultColors[i];
            assertTrue("colour " + i + " -> red family, hue " + hue(c), nearRed(hue(c)));
            reds.add(c);
        }
        assertEquals("all 12 coloured entries stay distinct", 12, reds.size());

        // Truecolor and 256-table entries go through the same mapping.
        assertTrue(nearRed(hue(scheme.remapIfEnabled(0xff00ff00))));
        assertTrue(nearRed(hue(scheme.mDefaultColors[46]))); // 256-table pure green
    }

    public void testExplicitEntriesWinAndRemapIsOptional() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        Properties props = new Properties();
        props.put("remap", "teal-red");
        props.put("color2", "#123456");
        scheme.updateWith(props);
        assertEquals(0xff123456, scheme.mDefaultColors[2]);

        scheme.updateWith(new Properties());
        assertFalse(scheme.isRemapEnabled());
        assertEquals(0xff00ff00, scheme.remapIfEnabled(0xff00ff00));
    }
}
