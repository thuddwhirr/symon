/*
 * Copyright (c) 2025 Waffle2e Computer Project
 * Based on Symon - A 6502 System Simulator
 * Copyright (c) 2008-2025 Seth J. Morabito <web@loomcom.com>
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

package com.loomcom.symon.devices;

import com.loomcom.symon.exceptions.MemoryAccessException;
import com.loomcom.symon.exceptions.MemoryRangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VibesGraphicsArray Video Controller for Waffle2e Computer
 *
 * Memory mapped at $4000-$400F (16 bytes):
 * $4000 - Mode Control Register
 * $4001 - Instruction Register
 * $4002-$400B - Argument Registers 0-9
 * $400C-$400E - Result Registers 0-2 (read-only)
 * $400F - Status Register (read-only)
 *
 * Supports text and graphics modes with command-based interface.
 */
public class VibesGraphicsArray extends Device {

    private static final Logger logger = LoggerFactory.getLogger(VibesGraphicsArray.class.getName());

    // Register offsets
    private static final int REG_MODE = 0x00;        // $4000
    private static final int REG_INSTRUCTION = 0x01; // $4001
    private static final int REG_ARG0 = 0x02;        // $4002
    private static final int REG_ARG1 = 0x03;        // $4003
    private static final int REG_ARG2 = 0x04;        // $4004
    private static final int REG_ARG3 = 0x05;        // $4005
    private static final int REG_ARG4 = 0x06;        // $4006
    private static final int REG_ARG5 = 0x07;        // $4007
    private static final int REG_ARG6 = 0x08;        // $4008
    private static final int REG_ARG7 = 0x09;        // $4009
    private static final int REG_ARG8 = 0x0A;        // $400A
    private static final int REG_ARG9 = 0x0B;        // $400B
    private static final int REG_RESULT0 = 0x0C;     // $400C (read-only)
    private static final int REG_RESULT1 = 0x0D;     // $400D (read-only)
    private static final int REG_RESULT2 = 0x0E;     // $400E (read-only)
    private static final int REG_STATUS = 0x0F;      // $400F (read-only)
    
    // Status register bits
    private static final int STATUS_BUSY = 0x01;
    private static final int STATUS_ERROR = 0x02;
    private static final int STATUS_READY = 0x80;
    
    // Mode register bits
    private static final int MODE_MASK = 0x07;      // Bits 0-2: Video mode (0-4)
    private static final int ACTIVE_PAGE = 0x08;    // Bit 3: Active page (display)
    private static final int WORKING_PAGE = 0x10;   // Bit 4: Working page (CPU writes)
    private static final int GRAPHICS_MODE = 0x80;  // Bit 7: 0=text mode, 1=graphics mode
    
    // Instruction opcodes
    private static final int INSTR_TEXT_WRITE = 0x00;      // Write character to screen
    private static final int INSTR_TEXT_POSITION = 0x01;   // Set cursor position
    private static final int INSTR_TEXT_CLEAR = 0x02;      // Clear screen with attributes
    private static final int INSTR_GET_TEXT_AT = 0x03;     // Read character at position
    private static final int INSTR_TEXT_COMMAND = 0x04;    // Process ASCII control characters
    private static final int INSTR_WRITE_PIXEL = 0x10;     // Write pixel at cursor
    private static final int INSTR_PIXEL_POS = 0x11;       // Set pixel cursor position
    private static final int INSTR_WRITE_PIXEL_POS = 0x12; // Set position and write pixel
    private static final int INSTR_CLEAR_SCREEN = 0x13;    // Clear graphics screen
    private static final int INSTR_GET_PIXEL_AT = 0x14;    // Read pixel at position
    private static final int INSTR_SET_PALETTE_ENTRY = 0x20; // Set 256-color palette entry
    private static final int INSTR_GET_PALETTE_ENTRY = 0x21; // Get 256-color palette entry

    // Registers
    private int modeRegister = 0;
    private int instructionRegister = 0;
    private final int[] argumentRegisters = new int[10];
    private final int[] resultRegisters = new int[3];
    private int statusRegister = STATUS_READY;
    
    // Text mode state (80x30 characters) - Mode 0
    private final char[][] textBuffer = new char[31][80];  // 30 + 1 scroll line
    private final int[][] textColorBuffer = new int[31][80];
    private int textCursorX = 0;
    private int textCursorY = 0;
    
    // Graphics mode buffers by mode
    private final int[][][] mode1Buffer = new int[2][480][640];  // 2 pages, 1 bit/pixel
    private final int[][] mode2Buffer = new int[480][640];        // 1 page, 2 bits/pixel
    private final int[][][] mode3Buffer = new int[2][240][320];   // 2 pages, 4 bits/pixel
    private final int[][] mode4Buffer = new int[240][320];        // 1 page, 8 bits/pixel (now uses palette)

    // 256-color palette for Mode 4 (12-bit RGB values: RRRR GGGG BBBB)
    private final int[] palette256 = new int[256];

    private int pixelCursorX = 0;
    private int pixelCursorY = 0;
    
    public VibesGraphicsArray(int startAddress) throws MemoryRangeException {
        super(startAddress, startAddress + 0x0F, "VibesGraphicsArray");
        
        // Initialize text buffer to spaces
        for (int y = 0; y < 31; y++) {
            for (int x = 0; x < 80; x++) {
                textBuffer[y][x] = ' ';
                textColorBuffer[y][x] = 0x01; // White (1) on black (0)
            }
        }
        
        // Initialize graphics buffers
        initializeGraphicsBuffers();

        // Initialize 256-color palette
        initializePalette256();

        logger.info("VibesGraphicsArray initialized at {}-{}", String.format("%04X", startAddress), String.format("%04X", startAddress + 0x0F));
    }
    
    @Override
    public void write(int address, int data) throws MemoryAccessException {
        // logger.debug("Writing to VGA {} = {}", String.format("%02X", address),String.format("%02X", data));

        switch (address) {
            case REG_MODE:
                int oldMode = modeRegister;
                modeRegister = data & 0xFF;
                // logger.debug("Mode register set to {}", String.format("%02X", modeRegister));
                
                // Notify listeners if video mode changed
                if ((oldMode & MODE_MASK) != (modeRegister & MODE_MASK) ||
                    (oldMode & ACTIVE_PAGE) != (modeRegister & ACTIVE_PAGE)) {
                    notifyListeners();
                }
                break;
                
            case REG_INSTRUCTION:
                instructionRegister = data & 0xFF;
                // Don't execute here - wait for trigger register
                break;
                
            case REG_ARG0: case REG_ARG1: case REG_ARG2: case REG_ARG3: case REG_ARG4:
            case REG_ARG5: case REG_ARG6: case REG_ARG7: case REG_ARG8: case REG_ARG9:
                int argIndex = address - REG_ARG0;
                argumentRegisters[argIndex] = data & 0xFF;
                
                // Execute instruction if this is the trigger register
                if (shouldExecuteInstruction(instructionRegister, argIndex)) {
                    executeInstruction();
                }
                break;
                
            case REG_RESULT0: case REG_RESULT1: case REG_RESULT2: case REG_STATUS:
                // Read-only registers - ignore writes
                logger.warn("Attempted write to read-only register at offset {}", String.format("%02X", address));
                break;
                
            default:
                logger.warn("Write to invalid video controller register at {}", String.format("%02X", address));
                break;
        }
        
        notifyListeners();
    }
    
    @Override
    public int read(int address, boolean cpuAccess) throws MemoryAccessException {
        switch (address) {
            case REG_MODE:
                return modeRegister;
                
            case REG_INSTRUCTION:
                return instructionRegister;
                
            case REG_ARG0: case REG_ARG1: case REG_ARG2: case REG_ARG3: case REG_ARG4:
            case REG_ARG5: case REG_ARG6: case REG_ARG7: case REG_ARG8: case REG_ARG9:
                int argIndex = address - REG_ARG0;
                return argumentRegisters[argIndex];
                
            case REG_RESULT0: case REG_RESULT1: case REG_RESULT2:
                int resultIndex = address - REG_RESULT0;
                return resultRegisters[resultIndex];
                
            case REG_STATUS:
                return statusRegister;
                
            default:
                logger.warn("Read from invalid video controller register at {}", String.format("%02X", address));
                return 0;
        }
    }
    
    // Check if writing to this argument register should trigger instruction execution
    private boolean shouldExecuteInstruction(int instruction, int argIndex) {
        switch (instruction) {
            case INSTR_TEXT_WRITE:      // $00 - Execute on ARG1 ($4003) write
                return argIndex == 1;
            case INSTR_TEXT_POSITION:   // $01 - Execute on ARG1 ($4003) write
                return argIndex == 1;
            case INSTR_TEXT_CLEAR:      // $02 - Execute on ARG0 ($4002) write
                return argIndex == 0;
            case INSTR_GET_TEXT_AT:     // $03 - Execute on ARG1 ($4003) write
                return argIndex == 1;
            case INSTR_TEXT_COMMAND:    // $04 - Execute on ARG0 ($4002) write
                return argIndex == 0;
            case INSTR_WRITE_PIXEL:     // $10 - Execute on ARG0 ($4002) write
                return argIndex == 0;
            case INSTR_PIXEL_POS:       // $11 - Execute on ARG3 ($4005) write
                return argIndex == 3;
            case INSTR_WRITE_PIXEL_POS: // $12 - Execute on ARG4 ($4006) write
                return argIndex == 4;
            case INSTR_CLEAR_SCREEN:    // $13 - Execute on ARG0 ($4002) write
                return argIndex == 0;
            case INSTR_GET_PIXEL_AT:    // $14 - Execute on ARG3 ($4005) write
                return argIndex == 3;
            case INSTR_SET_PALETTE_ENTRY: // $20 - Execute on ARG2 ($4004) write (RGB high byte)
                return argIndex == 2;
            case INSTR_GET_PALETTE_ENTRY: // $21 - Execute on ARG0 ($4002) write (palette index)
                return argIndex == 0;
            default:
                return false; // Unknown instruction
        }
    }

    private void executeInstruction() {
        statusRegister |= STATUS_BUSY;
        statusRegister &= ~STATUS_ERROR;
        
        try {
            switch (instructionRegister) {
                case INSTR_TEXT_WRITE:
                    executeTextWrite();
                    break;
                    
                case INSTR_TEXT_POSITION:
                    executeTextPosition();
                    break;
                    
                case INSTR_TEXT_CLEAR:
                    executeTextClear();
                    break;
                    
                case INSTR_GET_TEXT_AT:
                    executeGetTextAt();
                    break;

                case INSTR_TEXT_COMMAND:
                    executeTextCommand();
                    break;

                case INSTR_WRITE_PIXEL:
                    executeWritePixel();
                    break;
                    
                case INSTR_PIXEL_POS:
                    executePixelPosition();
                    break;
                    
                case INSTR_WRITE_PIXEL_POS:
                    executeWritePixelPos();
                    break;
                    
                case INSTR_CLEAR_SCREEN:
                    executeClearScreen();
                    break;
                    
                case INSTR_GET_PIXEL_AT:
                    executeGetPixelAt();
                    break;

                case INSTR_SET_PALETTE_ENTRY:
                    executeSetPaletteEntry();
                    break;

                case INSTR_GET_PALETTE_ENTRY:
                    executeGetPaletteEntry();
                    break;

                default:
                    logger.warn("Unknown instruction {}", String.format("%02X", instructionRegister));
                    statusRegister |= STATUS_ERROR;
                    break;
            }
        } catch (Exception e) {
            logger.error("Error executing instruction {}: {}", String.format("%02X", instructionRegister), e.getMessage());
            statusRegister |= STATUS_ERROR;
        }
        
        statusRegister &= ~STATUS_BUSY;
        // logger.debug("Executed instruction {}", String.format("%02X", instructionRegister));
    }
    
    private void executeTextWrite() {
        int color = argumentRegisters[0];        // ARG0 = attributes
        char character = (char) argumentRegisters[1];  // ARG1 = character code

        if (textCursorY < 30 && textCursorX < 80) {
            textBuffer[textCursorY][textCursorX] = character;
            textColorBuffer[textCursorY][textCursorX] = color;

            textCursorX++;
            if (textCursorX >= 80) {
                textCursorX = 0;
                textCursorY++;
                if (textCursorY >= 30) {
                    textCursorY = 29;
                    scrollTextUp();
                }
            }
        }

        // Notify UI to refresh
        notifyListeners();
    }
    
    private void executeTextPosition() {
        textCursorX = argumentRegisters[0];
        textCursorY = argumentRegisters[1];
        
        if (textCursorX >= 80) textCursorX = 79;
        if (textCursorY >= 30) textCursorY = 29;
    }
    
    private void executeTextClear() {
        char fillChar = (char) argumentRegisters[0];
        int color = argumentRegisters[1];

        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 80; x++) {
                textBuffer[y][x] = fillChar;
                textColorBuffer[y][x] = color;
            }
        }
        textCursorX = 0;
        textCursorY = 0;

        // Notify UI to refresh
        notifyListeners();
    }

    private void executeTextCommand() {
        int controlCode = argumentRegisters[0];

        switch (controlCode) {
            case 0x08: // BS - Backspace
                if (textCursorX > 0) {
                    textCursorX--;
                    textBuffer[textCursorY][textCursorX] = ' ';
                    textColorBuffer[textCursorY][textCursorX] = 0x01; // White on black
                }
                break;

            case 0x09: // HT - Horizontal Tab
                int nextTab = ((textCursorX / 8) + 1) * 8;
                if (nextTab < 80) {
                    textCursorX = nextTab;
                } else {
                    // Tab wraps to next line if it exceeds column 79
                    textCursorX = 0;
                    if (textCursorY < 29) {
                        textCursorY++;
                    } else {
                        // Scroll up one line
                        scrollTextUp();
                    }
                }
                break;

            case 0x0A: // LF - Line Feed
                textCursorX = 0;
                if (textCursorY < 29) {
                    textCursorY++;
                } else {
                    // Scroll up one line
                    scrollTextUp();
                }
                break;

            case 0x0D: // CR - Carriage Return
                textCursorX = 0;
                break;

            case 0x7F: // DEL - Delete
                textBuffer[textCursorY][textCursorX] = ' ';
                textColorBuffer[textCursorY][textCursorX] = 0x01; // White on black
                break;

            default:
                // Invalid control codes are ignored
                logger.debug("Invalid control code: 0x{}", Integer.toHexString(controlCode));
                break;
        }

        // Notify UI to refresh
        notifyListeners();
    }

    private void executeGetTextAt() {
        int x = argumentRegisters[0];
        int y = argumentRegisters[1];

        if (x < 80 && y < 30) {
            resultRegisters[0] = textBuffer[y][x];
            resultRegisters[1] = textColorBuffer[y][x];
        } else {
            statusRegister |= STATUS_ERROR;
        }
    }

    private void executeWritePixel() {
        int color = argumentRegisters[0];
        int videoMode = modeRegister & MODE_MASK;
        
        writePixelToCurrentMode(pixelCursorX, pixelCursorY, color, videoMode);
        
        // Auto-advance pixel cursor to next position
        advancePixelCursor(videoMode);
    }
    
    private void executePixelPosition() {
        // FPGA expects big-endian: ARG0=X_high, ARG1=X_low, ARG2=Y_high, ARG3=Y_low
        // This matches the 6502 code which sends high byte first
        pixelCursorX = (argumentRegisters[0] << 8) | argumentRegisters[1];
        pixelCursorY = (argumentRegisters[2] << 8) | argumentRegisters[3];
        
        // Clamp to current mode dimensions
        int videoMode = modeRegister & MODE_MASK;
        switch (videoMode) {
            case 1: case 2:
                if (pixelCursorX >= 640) pixelCursorX = 639;
                if (pixelCursorY >= 480) pixelCursorY = 479;
                break;
            case 3: case 4:
                if (pixelCursorX >= 320) pixelCursorX = 319;
                if (pixelCursorY >= 240) pixelCursorY = 239;
                break;
        }
    }
    
    private void executeWritePixelPos() {
        executePixelPosition();
        executeWritePixel();
    }
    
    private void executeClearScreen() {
        int color = argumentRegisters[0];
        int videoMode = modeRegister & MODE_MASK;
        
        clearScreenForMode(color, videoMode);
        pixelCursorX = 0;
        pixelCursorY = 0;
    }
    
    private void executeGetPixelAt() {
        // FPGA expects big-endian: ARG0=X_high, ARG1=X_low, ARG2=Y_high, ARG3=Y_low
        int x = (argumentRegisters[0] << 8) | argumentRegisters[1];
        int y = (argumentRegisters[2] << 8) | argumentRegisters[3];
        int videoMode = modeRegister & MODE_MASK;

        int pixelValue = getPixelFromCurrentMode(x, y, videoMode);
        if (pixelValue != -1) {
            resultRegisters[0] = pixelValue & 0xFF;
            resultRegisters[1] = (pixelValue >> 8) & 0xFF;
        } else {
            statusRegister |= STATUS_ERROR;
        }
    }

    // SET_PALETTE_ENTRY: Set 256-color palette entry
    // ARG0: Palette index (0-255)
    // ARG1: RGB low byte (GGGG BBBB)
    // ARG2: RGB high byte (xxxx RRRR) - execute on write
    private void executeSetPaletteEntry() {
        int paletteIndex = argumentRegisters[0] & 0xFF;
        int rgbLow = argumentRegisters[1] & 0xFF;
        int rgbHigh = argumentRegisters[2] & 0xFF;

        // Combine into 12-bit RGB value: RRRR GGGG BBBB
        int rgbValue = ((rgbHigh & 0x0F) << 8) | ((rgbLow & 0xF0) << 0) | ((rgbLow & 0x0F) << 0);

        palette256[paletteIndex] = rgbValue;

        logger.debug("Set palette[{}] = ${}", paletteIndex, String.format("%03X", rgbValue));
    }

    // GET_PALETTE_ENTRY: Get 256-color palette entry
    // ARG0: Palette index (0-255) - execute on write
    // Returns RGB low byte in RESULT0, RGB high byte in RESULT1
    private void executeGetPaletteEntry() {
        int paletteIndex = argumentRegisters[0] & 0xFF;
        int rgbValue = palette256[paletteIndex];

        // Split 12-bit RGB (RRRR GGGG BBBB) back into two bytes
        int rgbLow = ((rgbValue & 0x0F0) << 0) | ((rgbValue & 0x00F) << 0);  // GGGG BBBB
        int rgbHigh = (rgbValue & 0xF00) >> 8;                              // xxxx RRRR

        resultRegisters[0] = rgbLow & 0xFF;
        resultRegisters[1] = rgbHigh & 0xFF;

        logger.debug("Get palette[{}] = ${} (low={}, high={})", paletteIndex,
                    String.format("%03X", rgbValue), String.format("%02X", rgbLow), String.format("%02X", rgbHigh));
    }
    
    private void scrollTextUp() {
        // Move all lines up by one
        for (int y = 0; y < 29; y++) {
            System.arraycopy(textBuffer[y + 1], 0, textBuffer[y], 0, 80);
            System.arraycopy(textColorBuffer[y + 1], 0, textColorBuffer[y], 0, 80);
        }
        
        // Clear bottom line
        for (int x = 0; x < 80; x++) {
            textBuffer[29][x] = ' ';
            textColorBuffer[29][x] = 0x01; // White on black
        }
    }

    @Override
    public String toString() {
        return String.format("VibesGraphicsArray [Mode: %02X, Status: %02X, Cursor: (%d,%d)]", 
                           modeRegister, statusRegister, textCursorX, textCursorY);
    }
    
    // Initialize graphics buffers
    private void initializeGraphicsBuffers() {
        // Mode 1 buffers (640x480x2, 2 pages)
        for (int page = 0; page < 2; page++) {
            for (int y = 0; y < 480; y++) {
                for (int x = 0; x < 640; x++) {
                    mode1Buffer[page][y][x] = 0;
                }
            }
        }
        
        // Mode 2 buffer (640x480x4, 1 page)
        for (int y = 0; y < 480; y++) {
            for (int x = 0; x < 640; x++) {
                mode2Buffer[y][x] = 0;
            }
        }
        
        // Mode 3 buffers (320x240x16, 2 pages)
        for (int page = 0; page < 2; page++) {
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    mode3Buffer[page][y][x] = 0;
                }
            }
        }
        
        // Mode 4 buffer (320x240x256, 1 page) - now 8-bit for palette indexing
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                mode4Buffer[y][x] = 0;
            }
        }
    }

    // Initialize 256-color palette with historically accurate VGA palette
    private void initializePalette256() {
        // VGA Mode 13h default palette - exact 24-bit RGB values from
        // https://github.com/fzipp/vga/blob/main/palette.go
        // Converting from 8-bit per channel to 4-bit per channel in code
        int[] vgaPalette24bit = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
            0x000000, 0x141414, 0x202020, 0x2C2C2C, 0x383838, 0x454545, 0x515151, 0x616161,
            0x717171, 0x828282, 0x929292, 0xA2A2A2, 0xB6B6B6, 0xCBCBCB, 0xE3E3E3, 0xFFFFFF,
            0x0000FF, 0x4100FF, 0x7D00FF, 0xBE00FF, 0xFF00FF, 0xFF00BE, 0xFF007D, 0xFF0041,
            0xFF0000, 0xFF4100, 0xFF7D00, 0xFFBE00, 0xFFFF00, 0xBEFF00, 0x7DFF00, 0x41FF00,
            0x00FF00, 0x00FF41, 0x00FF7D, 0x00FFBE, 0x00FFFF, 0x00BEFF, 0x007DFF, 0x0041FF,
            0x7D7DFF, 0x9E7DFF, 0xBE7DFF, 0xDF7DFF, 0xFF7DFF, 0xFF7DDF, 0xFF7DBE, 0xFF7D9E,
            0xFF7D7D, 0xFF9E7D, 0xFFBE7D, 0xFFDF7D, 0xFFFF7D, 0xDFFF7D, 0xBEFF7D, 0x9EFF7D,
            0x7DFF7D, 0x7DFF9E, 0x7DFFBE, 0x7DFFDF, 0x7DFFFF, 0x7DDFFF, 0x7DBEFF, 0x7D9EFF,
            0xB6B6FF, 0xC7B6FF, 0xDBB6FF, 0xEBB6FF, 0xFFB6FF, 0xFFB6EB, 0xFFB6DB, 0xFFB6C7,
            0xFFB6B6, 0xFFC7B6, 0xFFDBB6, 0xFFEBB6, 0xFFFFB6, 0xEBFFB6, 0xDBFFB6, 0xC7FFB6,
            0xB6FFB6, 0xB6FFC7, 0xB6FFDB, 0xB6FFEB, 0xB6FFFF, 0xB6EBFF, 0xB6DBFF, 0xB6C7FF,
            0x000071, 0x1C0071, 0x380071, 0x550071, 0x710071, 0x710055, 0x710038, 0x71001C,
            0x710000, 0x711C00, 0x713800, 0x715500, 0x717100, 0x557100, 0x387100, 0x1C7100,
            0x007100, 0x00711C, 0x007138, 0x007155, 0x007171, 0x005571, 0x003871, 0x001C71,
            0x383871, 0x453871, 0x553871, 0x613871, 0x713871, 0x713861, 0x713855, 0x713845,
            0x713838, 0x714538, 0x715538, 0x716138, 0x717138, 0x617138, 0x557138, 0x457138,
            0x387138, 0x387145, 0x387155, 0x387161, 0x387171, 0x386171, 0x385571, 0x384571,
            0x515171, 0x595171, 0x615171, 0x695171, 0x715171, 0x715169, 0x715161, 0x715159,
            0x715151, 0x715951, 0x716151, 0x716951, 0x717151, 0x697151, 0x617151, 0x597151,
            0x517151, 0x517159, 0x517161, 0x517169, 0x517171, 0x516971, 0x516171, 0x515971,
            0x000041, 0x100041, 0x200041, 0x300041, 0x410041, 0x410030, 0x410020, 0x410010,
            0x410000, 0x411000, 0x412000, 0x413000, 0x414100, 0x304100, 0x204100, 0x104100,
            0x004100, 0x004110, 0x004120, 0x004130, 0x004141, 0x003041, 0x002041, 0x001041,
            0x202041, 0x282041, 0x302041, 0x382041, 0x412041, 0x412038, 0x412030, 0x412028,
            0x412020, 0x412820, 0x413020, 0x413820, 0x414120, 0x384120, 0x304120, 0x284120,
            0x204120, 0x204128, 0x204130, 0x204138, 0x204141, 0x203841, 0x203041, 0x202841,
            0x2C2C41, 0x302C41, 0x342C41, 0x3C2C41, 0x412C41, 0x412C3C, 0x412C34, 0x412C30,
            0x412C2C, 0x41302C, 0x41342C, 0x413C2C, 0x41412C, 0x3C412C, 0x34412C, 0x30412C,
            0x2C412C, 0x2C4130, 0x2C4134, 0x2C413C, 0x2C4141, 0x2C3C41, 0x2C3441, 0x2C3041,
            0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
            0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
            0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000
        };

        // Convert 24-bit RGB to 12-bit RGB (4 bits per channel)
        for (int i = 0; i < 256; i++) {
            int color24 = vgaPalette24bit[i];
            int r8 = (color24 >> 16) & 0xFF;  // Extract 8-bit red
            int g8 = (color24 >> 8) & 0xFF;   // Extract 8-bit green
            int b8 = color24 & 0xFF;          // Extract 8-bit blue

            // Convert 8-bit to 4-bit by shifting right by 4
            int r4 = r8 >> 4;
            int g4 = g8 >> 4;
            int b4 = b8 >> 4;

            // Combine into 12-bit value: RRRR GGGG BBBB
            palette256[i] = (r4 << 8) | (g4 << 4) | b4;
        }
    }
    
    // Write pixel to appropriate mode buffer
    private void writePixelToCurrentMode(int x, int y, int color, int mode) {
        int workingPage = getWorkingPage();
        
        switch (mode) {
            case 1: // 640x480x2, 2 pages
                if (x < 640 && y < 480) {
                    mode1Buffer[workingPage][y][x] = color & 0x01;
                }
                break;
                
            case 2: // 640x480x4, 1 page
                if (x < 640 && y < 480) {
                    mode2Buffer[y][x] = color & 0x03;
                }
                break;
                
            case 3: // 320x240x16, 2 pages
                if (x < 320 && y < 240) {
                    mode3Buffer[workingPage][y][x] = color & 0x0F;
                }
                break;
                
            case 4: // 320x240x256, 1 page (palette indexed)
                if (x < 320 && y < 240) {
                    mode4Buffer[y][x] = color & 0xFF;
                }
                break;
        }
    }
    
    // Get pixel from appropriate mode buffer
    private int getPixelFromCurrentMode(int x, int y, int mode) {
        int activePage = getActivePage();
        
        switch (mode) {
            case 1: // 640x480x2, 2 pages
                if (x < 640 && y < 480) {
                    return mode1Buffer[activePage][y][x];
                }
                break;
                
            case 2: // 640x480x4, 1 page
                if (x < 640 && y < 480) {
                    return mode2Buffer[y][x];
                }
                break;
                
            case 3: // 320x240x16, 2 pages
                if (x < 320 && y < 240) {
                    return mode3Buffer[activePage][y][x];
                }
                break;
                
            case 4: // 320x240x64, 1 page
                if (x < 320 && y < 240) {
                    return mode4Buffer[y][x];
                }
                break;
        }
        return -1; // Error
    }
    
    // Advance pixel cursor to next position with wrapping
    private void advancePixelCursor(int mode) {
        pixelCursorX++;
        
        switch (mode) {
            case 1: case 2: // 640x480 modes
                if (pixelCursorX >= 640) {
                    pixelCursorX = 0;
                    pixelCursorY++;
                    if (pixelCursorY >= 480) {
                        pixelCursorY = 0; // Wrap to top of screen
                    }
                }
                break;
                
            case 3: case 4: // 320x240 modes
                if (pixelCursorX >= 320) {
                    pixelCursorX = 0;
                    pixelCursorY++;
                    if (pixelCursorY >= 240) {
                        pixelCursorY = 0; // Wrap to top of screen
                    }
                }
                break;
        }
    }
    
    // Clear screen for current mode
    private void clearScreenForMode(int color, int mode) {
        int workingPage = getWorkingPage();
        
        switch (mode) {
            case 1:
                for (int y = 0; y < 480; y++) {
                    for (int x = 0; x < 640; x++) {
                        mode1Buffer[workingPage][y][x] = color & 0x01;
                    }
                }
                break;
                
            case 2:
                for (int y = 0; y < 480; y++) {
                    for (int x = 0; x < 640; x++) {
                        mode2Buffer[y][x] = color & 0x03;
                    }
                }
                break;
                
            case 3:
                for (int y = 0; y < 240; y++) {
                    for (int x = 0; x < 320; x++) {
                        mode3Buffer[workingPage][y][x] = color & 0x0F;
                    }
                }
                break;
                
            case 4:
                for (int y = 0; y < 240; y++) {
                    for (int x = 0; x < 320; x++) {
                        mode4Buffer[y][x] = color & 0xFF;
                    }
                }
                break;
        }
    }
    
    // Public accessors for UI integration
    public char[][] getTextBuffer() {
        return textBuffer;
    }
    
    public int[][] getTextColorBuffer() {
        return textColorBuffer;
    }
    
    public int getVideoMode() {
        return modeRegister & MODE_MASK;
    }
    
    public int getActivePage() {
        return (modeRegister & ACTIVE_PAGE) != 0 ? 1 : 0;
    }
    
    public int getWorkingPage() {
        return (modeRegister & WORKING_PAGE) != 0 ? 1 : 0;
    }
    
    public int[][][] getMode1Buffer() {
        return mode1Buffer;
    }
    
    public int[][] getMode2Buffer() {
        return mode2Buffer;
    }
    
    public int[][][] getMode3Buffer() {
        return mode3Buffer;
    }
    
    public int[][] getMode4Buffer() {
        return mode4Buffer;
    }
    
    public int getTextCursorX() {
        return textCursorX;
    }
    
    public int getTextCursorY() {
        return textCursorY;
    }

    public int[] getPalette256() {
        return palette256;
    }
}