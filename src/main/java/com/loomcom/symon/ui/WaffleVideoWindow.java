/*
 * Copyright (c) 2025 Waffle2e Computer Project
 * Based on Symon VideoWindow - Copyright (c) 2008-2025 Seth J. Morabito <web@loomcom.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.loomcom.symon.ui;

import com.loomcom.symon.devices.DeviceChangeListener;
import com.loomcom.symon.devices.VibesGraphicsArray;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WaffleVideoWindow represents the Waffle2e VibesGraphicsArray display output.
 * 
 * Supports 5 video modes:
 * - Mode 0: Text mode 80x30 characters
 * - Mode 1: Graphics 640x480x2 colors, 2 pages  
 * - Mode 2: Graphics 640x480x4 colors, 1 page
 * - Mode 3: Graphics 320x240x16 colors, 2 pages
 * - Mode 4: Graphics 320x240x64 colors, 1 page
 */
public class WaffleVideoWindow extends JFrame implements DeviceChangeListener {

    private static final Logger logger = Logger.getLogger(WaffleVideoWindow.class.getName());

    private static final long WINDOW_REPAINT_INTERVAL = 33; // 30fps rate
    
    // Text mode constants
    private static final int TEXT_COLS = 80;
    private static final int TEXT_ROWS = 30;
    private static final int CHAR_WIDTH = 8;
    private static final int CHAR_HEIGHT = 16;
    private static final String FONT_FILE = "/font_8x16.mem";
    
    // Graphics mode constants
    private static final int HIRES_WIDTH = 640;
    private static final int HIRES_HEIGHT = 480; 
    private static final int LORES_WIDTH = 320;
    private static final int LORES_HEIGHT = 240;

    // Default 16-color EGA palette as 24-bit RGB
    private static final int[] EGA_PALETTE = {
        0x000000,  // 0: Black
        0xFFFFFF,  // 1: White  
        0x00FF00,  // 2: Bright Green
        0x008000,  // 3: Dark Green
        0xFF0000,  // 4: Red
        0x0000FF,  // 5: Blue
        0xFFFF00,  // 6: Yellow
        0xFF00FF,  // 7: Magenta
        0x00FFFF,  // 8: Cyan
        0x800000,  // 9: Dark Red
        0x000080,  // 10: Dark Blue
        0x808000,  // 11: Brown
        0x808080,  // 12: Gray
        0x404040,  // 13: Dark Gray
        0x80FF80,  // 14: Light Green
        0x8080FF   // 15: Light Blue
    };

    private final int scaleX, scaleY;
    private final boolean shouldScale;
    
    private BufferedImage displayImage;
    private Dimension windowDimensions;
    private final VibesGraphicsArray vga;
    private final int[][] fontData; // [character][row] -> 8 pixels as int
    
    private final ScheduledExecutorService scheduler;
    
    // Current video state
    private int currentVideoMode = 0;
    private int currentActivePage = 0;
    private int currentWorkingPage = 0;

    /**
     * Video panel that renders the current video mode output
     */
    private class VideoPanel extends JPanel {
        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            if (displayImage != null) {
                Graphics2D g2d = (Graphics2D) g;
                if (shouldScale) {
                    g2d.scale(scaleX, scaleY);
                }
                g2d.drawImage(displayImage, 0, 0, null);
            }
        }

        @Override
        public Dimension getMinimumSize() {
            return windowDimensions;
        }

        @Override
        public Dimension getPreferredSize() {
            return windowDimensions;
        }
    }

    /**
     * Runnable task that triggers window repaints
     */
    private class WindowPainter implements Runnable {
        public void run() {
            SwingUtilities.invokeLater(() -> {
                if (WaffleVideoWindow.this.isVisible()) {
                    updateDisplay();
                    WaffleVideoWindow.this.repaint();
                }
            });
        }
    }

    public WaffleVideoWindow(VibesGraphicsArray vga, int scaleX, int scaleY) throws IOException {
        this.vga = vga;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.shouldScale = (scaleX > 1 || scaleY > 1);
        
        // Load font data
        this.fontData = loadFontData();
        
        vga.registerListener(this);
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Start with text mode
        buildDisplayImage();
        
        createAndShowUi();
        
        // Schedule regular repaints
        scheduler.scheduleAtFixedRate(new WindowPainter(),
                WINDOW_REPAINT_INTERVAL,
                WINDOW_REPAINT_INTERVAL,
                TimeUnit.MILLISECONDS);
        
        logger.info("WaffleVideoWindow initialized");
    }

    /**
     * Called by the VGA device when its state changes
     */
    @Override
    public void deviceStateChanged() {
        // Check if video mode changed and rebuild display if needed
        int newMode = extractVideoMode();
        if (newMode != currentVideoMode) {
            currentVideoMode = newMode;
            buildDisplayImage();
            SwingUtilities.invokeLater(() -> {
                pack();
                repaint();
            });
        }
        
        // Update page information
        currentActivePage = extractActivePage();
        currentWorkingPage = extractWorkingPage();
    }

    private void createAndShowUi() {
        setTitle("Waffle2e Video Display");
        
        int borderWidth = 20;
        int borderHeight = 20;

        JPanel containerPane = new JPanel();
        containerPane.setBorder(BorderFactory.createEmptyBorder(borderHeight, borderWidth, borderHeight, borderWidth));
        containerPane.setLayout(new BorderLayout());
        containerPane.setBackground(Color.BLACK);

        containerPane.add(new VideoPanel(), BorderLayout.CENTER);

        getContentPane().add(containerPane, BorderLayout.CENTER);
        setResizable(false);
        pack();
    }

    /**
     * Build the display image based on current video mode
     */
    private void buildDisplayImage() {
        // All modes use the same canvas size (640x480) for consistency
        int imageWidth = 640;
        int imageHeight = 480;
        
        this.displayImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        this.windowDimensions = new Dimension(imageWidth * scaleX, imageHeight * scaleY);
        
        logger.log(Level.FINE, "Built display image for mode " + currentVideoMode + ": " + imageWidth + "x" + imageHeight);
    }

    /**
     * Update the display image with current VGA state
     */
    private void updateDisplay() {
        if (displayImage == null) {
            return;
        }
        
        try {
            switch (currentVideoMode) {
                case 0:
                    renderTextMode();
                    break;
                case 1: case 2:
                    renderHiResGraphics();
                    break;
                case 3: case 4:
                    renderLoResGraphics();
                    break;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error updating display: " + e.getMessage(), e);
        }
    }

    /**
     * Render text mode (Mode 0)
     */
    private void renderTextMode() {
        Graphics2D g2d = displayImage.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, displayImage.getWidth(), displayImage.getHeight());
        
        // Render text characters with proper color attributes
        char[][] textBuffer = vga.getTextBuffer();
        int[][] colorBuffer = vga.getTextColorBuffer();
        
        // Render each character position
        for (int row = 0; row < Math.min(TEXT_ROWS, textBuffer.length); row++) {
            for (int col = 0; col < Math.min(TEXT_COLS, textBuffer[row].length); col++) {
                int colorAttr = colorBuffer[row][col];
                int bgColor = (colorAttr >> 4) & 0x0F;
                int fgColor = colorAttr & 0x0F;
                
                // Draw background
                g2d.setColor(new Color(EGA_PALETTE[bgColor]));
                g2d.fillRect(col * CHAR_WIDTH, row * CHAR_HEIGHT, CHAR_WIDTH, CHAR_HEIGHT);
                
                // Draw character glyph
                char ch = textBuffer[row][col];
                int charCode = (int)ch & 0xFF;
                
                // Render character glyph
                g2d.setColor(new Color(EGA_PALETTE[fgColor]));
                for (int glyphRow = 0; glyphRow < CHAR_HEIGHT; glyphRow++) {
                    int pixelData = fontData[charCode][glyphRow];
                    for (int glyphCol = 0; glyphCol < CHAR_WIDTH; glyphCol++) {
                        if ((pixelData & (1 << (7 - glyphCol))) != 0) {
                            g2d.fillRect(col * CHAR_WIDTH + glyphCol, 
                                       row * CHAR_HEIGHT + glyphRow, 1, 1);
                        }
                    }
                }
            }
        }
        
        g2d.dispose();
    }

    /**
     * Render high-resolution graphics modes (640x480)
     */
    private void renderHiResGraphics() {
        int activePage = vga.getActivePage();
        
        if (currentVideoMode == 1) {
            // Mode 1: 640x480x2 colors
            int[][][] buffer = vga.getMode1Buffer();
            for (int y = 0; y < 480; y++) {
                for (int x = 0; x < 640; x++) {
                    int colorIndex = buffer[activePage][y][x];
                    Color color = getPixelColor(colorIndex, currentVideoMode);
                    displayImage.setRGB(x, y, color.getRGB());
                }
            }
        } else if (currentVideoMode == 2) {
            // Mode 2: 640x480x4 colors
            int[][] buffer = vga.getMode2Buffer();
            for (int y = 0; y < 480; y++) {
                for (int x = 0; x < 640; x++) {
                    int colorIndex = buffer[y][x];
                    Color color = getPixelColor(colorIndex, currentVideoMode);
                    displayImage.setRGB(x, y, color.getRGB());
                }
            }
        }
    }

    /**
     * Render low-resolution graphics modes (320x240) scaled to 640x480  
     */
    private void renderLoResGraphics() {
        int activePage = vga.getActivePage();
        
        if (currentVideoMode == 3) {
            // Mode 3: 320x240x16 colors - scale 2x to fill 640x480 canvas
            int[][][] buffer = vga.getMode3Buffer();
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    int colorIndex = buffer[activePage][y][x];
                    Color color = getPixelColor(colorIndex, currentVideoMode);
                    int rgb = color.getRGB();
                    
                    // Render each logical pixel as a 2x2 block
                    displayImage.setRGB(x * 2, y * 2, rgb);
                    displayImage.setRGB(x * 2 + 1, y * 2, rgb);
                    displayImage.setRGB(x * 2, y * 2 + 1, rgb);
                    displayImage.setRGB(x * 2 + 1, y * 2 + 1, rgb);
                }
            }
        } else if (currentVideoMode == 4) {
            // Mode 4: 320x240x64 colors - scale 2x to fill 640x480 canvas
            int[][] buffer = vga.getMode4Buffer();
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    int color6bit = buffer[y][x];
                    Color color = getPixelColor(color6bit, currentVideoMode);
                    int rgb = color.getRGB();
                    
                    // Render each logical pixel as a 2x2 block
                    displayImage.setRGB(x * 2, y * 2, rgb);
                    displayImage.setRGB(x * 2 + 1, y * 2, rgb);
                    displayImage.setRGB(x * 2, y * 2 + 1, rgb);
                    displayImage.setRGB(x * 2 + 1, y * 2 + 1, rgb);
                }
            }
        }
    }

    /**
     * Convert 6-bit color to 24-bit RGB (for mode 4)
     */
    private int convert6BitToRGB(int color6bit) {
        int r2 = (color6bit >> 4) & 0x03;  // Red: bits 5-4
        int g2 = (color6bit >> 2) & 0x03;  // Green: bits 3-2  
        int b2 = color6bit & 0x03;         // Blue: bits 1-0
        
        // Scale 2-bit values (0-3) to 8-bit values (0-255)
        int r8 = r2 * 85;  // 0,85,170,255
        int g8 = g2 * 85;
        int b8 = b2 * 85;
        
        return (r8 << 16) | (g8 << 8) | b8;
    }

    /**
     * Get Java Color for pixel based on video mode
     */
    private Color getPixelColor(int colorValue, int videoMode) {
        switch (videoMode) {
            case 0: case 1: case 2: case 3:
                // Use palette lookup
                int paletteIndex = colorValue & 0x0F;
                return new Color(EGA_PALETTE[paletteIndex]);
                
            case 4:
                // Direct 6-bit RGB
                return new Color(convert6BitToRGB(colorValue));
                
            default:
                return Color.BLACK;
        }
    }

    private int extractVideoMode() {
        return vga.getVideoMode();
    }

    private int extractActivePage() {
        return vga.getActivePage();
    }

    private int extractWorkingPage() {
        return vga.getWorkingPage();
    }

    /**
     * Load font data from font_8x16.mem resource file
     */
    private int[][] loadFontData() throws IOException {
        var resource = this.getClass().getResourceAsStream(FONT_FILE);
        if (resource == null) {
            throw new IOException("Font resource " + FONT_FILE + " not found");
        }
        
        int[][] font = new int[256][16]; // 256 characters, 16 rows each
        
        try (BufferedInputStream bis = new BufferedInputStream(resource)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while (bis.available() > 0) {
                bos.write(bis.read());
            }
            
            String fontContent = bos.toString("UTF-8");
            String[] lines = fontContent.split("\n");
            
            for (int i = 0; i < lines.length && i < 4096; i++) { // 256 chars * 16 rows
                String line = lines[i].trim();
                if (line.length() == 8) {
                    int charIndex = i / 16;
                    int rowIndex = i % 16;
                    
                    int pixelData = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        if (line.charAt(bit) == '1') {
                            pixelData |= (1 << (7 - bit));
                        }
                    }
                    font[charIndex][rowIndex] = pixelData;
                }
            }
        }
        
        logger.log(Level.INFO, "Loaded font data: 256 characters, 8x16 pixels each");
        return font;
    }

    /**
     * Cleanup resources when window is closed
     */
    @Override
    public void dispose() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        super.dispose();
    }
}