
package renderers;
import util.PixelFont;

import static org.lwjgl.opengl.GL11.*;

public class PixelTextRenderer {
    public static void drawText(String text, float x, float y, float fontScale) {
        float startX = x;
        float charSpacing = fontScale - 1; // Space between letters
        float charWidth = 8 * fontScale; // Width of each char

        for (char c : text.toCharArray()) {
            drawChar(c, startX, y, fontScale);
            startX += charWidth + charSpacing;
        }
    }

    private static void drawChar(char c, float x, float y, float scale) {
        if (c < 0 || c >= 256)
            return;
        byte[] glyph = PixelFont.FONT[c];
        if (glyph == null)
            return;

        for (int row = 0; row < 8; row++) {
            byte line = glyph[row];
            for (int col = 0; col < 8; col++) {
                if ((line & (1 << (7 - col))) != 0) {
                    float px = x + col * scale;
                    float py = y - row * scale;

                    glBegin(GL_QUADS);
                    glVertex2f(px, py);
                    glVertex2f(px + scale, py);
                    glVertex2f(px + scale, py + scale);
                    glVertex2f(px, py + scale);
                    glEnd();
                }
            }
        }
    }

}
