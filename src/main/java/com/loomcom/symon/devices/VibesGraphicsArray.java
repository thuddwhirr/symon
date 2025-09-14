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
    
    // Instruction opcodes
    private static final int INSTR_TEXT_WRITE = 0x00;      // Write character to screen
    private static final int INSTR_TEXT_POSITION = 0x01;   // Set cursor position
    private static final int INSTR_TEXT_CLEAR = 0x02;      // Clear screen with attributes
    private static final int INSTR_GET_TEXT_AT = 0x03;     // Read character at position
    private static final int INSTR_WRITE_PIXEL = 0x10;     // Write pixel at cursor
    private static final int INSTR_PIXEL_POS = 0x11;       // Set pixel cursor position
    private static final int INSTR_WRITE_PIXEL_POS = 0x12; // Set position and write pixel
    private static final int INSTR_CLEAR_SCREEN = 0x13;    // Clear graphics screen
    private static final int INSTR_GET_PIXEL_AT = 0x14;    // Read pixel at position
    
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
    private final int[][] mode4Buffer = new int[240][320];        // 1 page, 6 bits/pixel
    
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
        
        logger.info("VibesGraphicsArray initialized at {}-{}", String.format("%04X", startAddress), String.format("%04X", startAddress + 0x0F));
    }
    
    @Override
    public void write(int address, int data) throws MemoryAccessException {
        logger.debug("Writing to VGA {} = {}", String.format("%02X", address),String.format("%02X", data));

        switch (address) {
            case REG_MODE:
                int oldMode = modeRegister;
                modeRegister = data & 0xFF;
                logger.debug("Mode register set to {}", String.format("%02X", modeRegister));
                
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
        logger.debug("Executed instruction {}", String.format("%02X", instructionRegister));
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
        pixelCursorX = argumentRegisters[0] | (argumentRegisters[1] << 8);
        pixelCursorY = argumentRegisters[2] | (argumentRegisters[3] << 8);
        
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
        int x = argumentRegisters[0] | (argumentRegisters[1] << 8);
        int y = argumentRegisters[2] | (argumentRegisters[3] << 8);
        int videoMode = modeRegister & MODE_MASK;
        
        int pixelValue = getPixelFromCurrentMode(x, y, videoMode);
        if (pixelValue != -1) {
            resultRegisters[0] = pixelValue & 0xFF;
            resultRegisters[1] = (pixelValue >> 8) & 0xFF;
        } else {
            statusRegister |= STATUS_ERROR;
        }
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
        
        // Mode 4 buffer (320x240x64, 1 page)
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                mode4Buffer[y][x] = 0;
            }
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
                
            case 4: // 320x240x64, 1 page
                if (x < 320 && y < 240) {
                    mode4Buffer[y][x] = color & 0x3F;
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
                        mode4Buffer[y][x] = color & 0x3F;
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
}