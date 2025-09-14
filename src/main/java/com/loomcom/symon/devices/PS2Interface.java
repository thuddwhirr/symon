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

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * PS/2 Interface for Waffle2e Computer using W65C22 VIA
 * 
 * Memory mapped at $4020-$4023 (4 bytes):
 * $4020 - PS/2 Data Register (keyboard/mouse data)
 * $4021 - PS/2 Status Register (status and flags)
 * $4022 - PS/2 Command Register (commands to devices)
 * $4023 - PS/2 Control Register (interface control)
 * 
 * Simulates PS/2 keyboard and mouse interface through VIA-based protocol.
 */
public class PS2Interface extends Device implements KeyListener {
    
    private static final Logger logger = LoggerFactory.getLogger(PS2Interface.class.getName());
    
    // Register offsets
    private static final int REG_DATA = 0x00;     // $4020
    private static final int REG_STATUS = 0x01;   // $4021
    private static final int REG_COMMAND = 0x02;  // $4022
    private static final int REG_CONTROL = 0x03;  // $4023
    
    // Status register bits
    private static final int STATUS_DATA_READY = 0x01;  // Data available to read
    private static final int STATUS_BUSY = 0x02;        // Interface busy
    private static final int STATUS_PARITY_ERR = 0x04;  // Parity error
    private static final int STATUS_TIMEOUT = 0x08;     // Communication timeout
    private static final int STATUS_INHIBIT = 0x10;     // Clock inhibit
    private static final int STATUS_READY = 0x80;       // Interface ready
    
    // PS/2 keyboard scan codes (Set 2)
    private static final int[] SCAN_CODE_MAP = new int[256];
    
    static {
        // Initialize scan code map for common keys
        SCAN_CODE_MAP[KeyEvent.VK_A] = 0x1C;
        SCAN_CODE_MAP[KeyEvent.VK_B] = 0x32;
        SCAN_CODE_MAP[KeyEvent.VK_C] = 0x21;
        SCAN_CODE_MAP[KeyEvent.VK_D] = 0x23;
        SCAN_CODE_MAP[KeyEvent.VK_E] = 0x24;
        SCAN_CODE_MAP[KeyEvent.VK_F] = 0x2B;
        SCAN_CODE_MAP[KeyEvent.VK_G] = 0x34;
        SCAN_CODE_MAP[KeyEvent.VK_H] = 0x33;
        SCAN_CODE_MAP[KeyEvent.VK_I] = 0x43;
        SCAN_CODE_MAP[KeyEvent.VK_J] = 0x3B;
        SCAN_CODE_MAP[KeyEvent.VK_K] = 0x42;
        SCAN_CODE_MAP[KeyEvent.VK_L] = 0x4B;
        SCAN_CODE_MAP[KeyEvent.VK_M] = 0x3A;
        SCAN_CODE_MAP[KeyEvent.VK_N] = 0x31;
        SCAN_CODE_MAP[KeyEvent.VK_O] = 0x44;
        SCAN_CODE_MAP[KeyEvent.VK_P] = 0x4D;
        SCAN_CODE_MAP[KeyEvent.VK_Q] = 0x15;
        SCAN_CODE_MAP[KeyEvent.VK_R] = 0x2D;
        SCAN_CODE_MAP[KeyEvent.VK_S] = 0x1B;
        SCAN_CODE_MAP[KeyEvent.VK_T] = 0x2C;
        SCAN_CODE_MAP[KeyEvent.VK_U] = 0x3C;
        SCAN_CODE_MAP[KeyEvent.VK_V] = 0x2A;
        SCAN_CODE_MAP[KeyEvent.VK_W] = 0x1D;
        SCAN_CODE_MAP[KeyEvent.VK_X] = 0x22;
        SCAN_CODE_MAP[KeyEvent.VK_Y] = 0x35;
        SCAN_CODE_MAP[KeyEvent.VK_Z] = 0x1A;
        
        SCAN_CODE_MAP[KeyEvent.VK_0] = 0x45;
        SCAN_CODE_MAP[KeyEvent.VK_1] = 0x16;
        SCAN_CODE_MAP[KeyEvent.VK_2] = 0x1E;
        SCAN_CODE_MAP[KeyEvent.VK_3] = 0x26;
        SCAN_CODE_MAP[KeyEvent.VK_4] = 0x25;
        SCAN_CODE_MAP[KeyEvent.VK_5] = 0x2E;
        SCAN_CODE_MAP[KeyEvent.VK_6] = 0x36;
        SCAN_CODE_MAP[KeyEvent.VK_7] = 0x3D;
        SCAN_CODE_MAP[KeyEvent.VK_8] = 0x3E;
        SCAN_CODE_MAP[KeyEvent.VK_9] = 0x46;
        
        SCAN_CODE_MAP[KeyEvent.VK_SPACE] = 0x29;
        SCAN_CODE_MAP[KeyEvent.VK_ENTER] = 0x5A;
        SCAN_CODE_MAP[KeyEvent.VK_ESCAPE] = 0x76;
        SCAN_CODE_MAP[KeyEvent.VK_BACK_SPACE] = 0x66;
        SCAN_CODE_MAP[KeyEvent.VK_TAB] = 0x0D;
        SCAN_CODE_MAP[KeyEvent.VK_SHIFT] = 0x12;
        SCAN_CODE_MAP[KeyEvent.VK_CONTROL] = 0x14;
        SCAN_CODE_MAP[KeyEvent.VK_ALT] = 0x11;
    }
    
    // Interface state
    private int dataRegister = 0;
    private int statusRegister = STATUS_READY;
    private int commandRegister = 0;
    private int controlRegister = 0;
    private boolean interrupt = false;
    
    // Keyboard input queue
    private final BlockingQueue<Integer> keyQueue = new LinkedBlockingQueue<>();
    
    public PS2Interface(int startAddress) throws MemoryRangeException {
        super(startAddress, startAddress + 0x03, "PS/2 Interface");
        logger.info("PS/2 Interface initialized at {}-{}", String.format("%04X", startAddress), String.format("%04X", startAddress + 0x03));
    }
    
    @Override
    public void write(int address, int data) throws MemoryAccessException {
        logger.info("PS/2 WRITE: Addr={} Data={}", String.format("%02X", address), String.format("%02X", data & 0xFF));

        switch (address) {
            case REG_DATA:
                dataRegister = data & 0xFF;
                // Writing to data register sends command to PS/2 device
                handlePS2Command(dataRegister);
                break;
                
            case REG_STATUS:
                // Status register is mostly read-only, but some bits can be cleared
                statusRegister &= ~(data & (STATUS_PARITY_ERR | STATUS_TIMEOUT));
                break;
                
            case REG_COMMAND:
                commandRegister = data & 0xFF;
                handleInterfaceCommand(commandRegister);
                break;
                
            case REG_CONTROL:
                controlRegister = data & 0xFF;
                handleControlRegister(controlRegister);
                break;
                
            default:
                logger.warn("Write to invalid PS/2 register at {}", String.format("%02X", address));
                break;
        }
        
        notifyListeners();
    }
    
    @Override
    public int read(int address, boolean cpuAccess) throws MemoryAccessException {
        logger.info("PS/2 READ: Addr={} Status={} Interrupt={}",
            String.format("%02X", address),
            String.format("%02X", statusRegister),
            interrupt);
        switch (address) {
            case REG_DATA:
                // Reading data register gets next byte from PS/2 device
                if (!keyQueue.isEmpty()) {
                    dataRegister = keyQueue.poll();
                    updateDataReadyStatus();
                }
                // Clear interrupt when data is read, but re-assert if more data available
                if (cpuAccess) {
                    interrupt = false;
                    // Schedule new interrupt if queue still has data
                    if (!keyQueue.isEmpty()) {
                        scheduleNextInterrupt();
                    }
                }
                return dataRegister;
                
            case REG_STATUS:
                return statusRegister;
                
            case REG_COMMAND:
                return commandRegister;
                
            case REG_CONTROL:
                return controlRegister;
                
            default:
                logger.warn("Read from invalid PS/2 register at {}", String.format("%02X", address));
                return 0;
        }
    }
    
    private void handlePS2Command(int command) {
        // Handle commands sent to PS/2 devices
        switch (command) {
            case 0xFF: // Reset
                keyQueue.clear();
                keyQueue.offer(0xFA); // ACK first
                keyQueue.offer(0xAA); // Then self-test passed
                updateDataReadyStatus();
                logger.debug("PS/2 device reset");
                break;
                
            case 0xF4: // Enable scanning
                keyQueue.offer(0xFA); // ACK
                updateDataReadyStatus();
                logger.debug("PS/2 scanning enabled");
                break;
                
            case 0xF5: // Disable scanning
                keyQueue.offer(0xFA); // ACK
                updateDataReadyStatus();
                logger.debug("PS/2 scanning disabled");
                break;
                
            case 0xF2: // Get device ID
                keyQueue.offer(0xFA); // ACK
                keyQueue.offer(0xAB); // Keyboard ID byte 1
                keyQueue.offer(0x83); // Keyboard ID byte 2
                updateDataReadyStatus();
                logger.debug("PS/2 device ID requested");
                break;
                
            default:
                // Unknown command, send NAK
                keyQueue.offer(0xFE);
                updateDataReadyStatus();
                logger.debug("Unknown PS/2 command: {}", String.format("%02X", command));
                break;
        }
    }
    
    private void handleInterfaceCommand(int command) {
        // Handle interface control commands
        logger.debug("PS/2 interface command: {}", String.format("%02X", command));
    }
    
    private void handleControlRegister(int control) {
        // Handle control register changes
        if ((control & STATUS_INHIBIT) != 0) {
            statusRegister |= STATUS_INHIBIT;
            logger.debug("PS/2 clock inhibited");
        } else {
            statusRegister &= ~STATUS_INHIBIT;
            logger.debug("PS/2 clock enabled");
        }
    }
    
    private void updateDataReadyStatus() {
        if (!keyQueue.isEmpty()) {
            statusRegister |= STATUS_DATA_READY;
            // Trigger interrupt when data becomes available
            if (!interrupt) {
                interrupt = true;
                getBus().assertIrq();
            }
        } else {
            statusRegister &= ~STATUS_DATA_READY;
            interrupt = false;
        }
    }
    
    private void scheduleNextInterrupt() {
        // Schedule a new interrupt after a brief delay to simulate real PS/2 timing
        // This ensures each scan code in the queue gets its own interrupt
        Thread interruptThread = new Thread(() -> {
            try {
                Thread.sleep(1); // Very brief delay - 1ms
                if (!keyQueue.isEmpty() && !interrupt) {
                    interrupt = true;
                    getBus().assertIrq();
                    logger.debug("Scheduled interrupt for queued PS/2 data");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        interruptThread.setDaemon(true);
        interruptThread.start();
    }
    
    // KeyListener interface implementation
    @Override
    public void keyPressed(KeyEvent e) {
        logger.info("KEY EVENT: {} (keyCode={})", KeyEvent.getKeyText(e.getKeyCode()), e.getKeyCode());
        int scanCode = SCAN_CODE_MAP[e.getKeyCode()];
        if (scanCode != 0) {
            keyQueue.offer(scanCode);
            updateDataReadyStatus();
            notifyListeners();
            logger.info("Key pressed: {} (scan code: {}) - SCAN_CODE_MAP[{}] = {}", 
                        KeyEvent.getKeyText(e.getKeyCode()), String.format("%02X", scanCode), 
                        e.getKeyCode(), String.format("%02X", scanCode));
        } else {
            logger.info("No scan code mapping for key: {} (keyCode={}) - SCAN_CODE_MAP[{}] = 0", 
                       KeyEvent.getKeyText(e.getKeyCode()), e.getKeyCode(), e.getKeyCode());
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) {
        int scanCode = SCAN_CODE_MAP[e.getKeyCode()];
        if (scanCode != 0) {
            keyQueue.offer(0xF0); // Break code prefix
            keyQueue.offer(scanCode);
            updateDataReadyStatus();
            notifyListeners();
            logger.debug("Key released: {} (scan code: {})", 
                        KeyEvent.getKeyText(e.getKeyCode()), String.format("%02X", scanCode));
        }
    }
    
    @Override
    public void keyTyped(KeyEvent e) {
        // Not used for scan code generation
    }
    
    // Simulate mouse input
    public void simulateMouseMove(int deltaX, int deltaY) {
        // PS/2 mouse packet: [flags][deltaX][deltaY]
        keyQueue.offer(0x08); // Basic flags (no buttons pressed)
        keyQueue.offer(deltaX & 0xFF);
        keyQueue.offer(deltaY & 0xFF);
        updateDataReadyStatus();
        notifyListeners();
    }
    
    public void simulateMouseClick(int button, boolean pressed) {
        int flags = 0x08; // Base flags
        if (button == 1) flags |= 0x01; // Left button
        if (button == 2) flags |= 0x02; // Right button  
        if (button == 3) flags |= 0x04; // Middle button
        
        keyQueue.offer(flags);
        keyQueue.offer(0x00); // No X movement
        keyQueue.offer(0x00); // No Y movement
        updateDataReadyStatus();
        notifyListeners();
    }
    
    @Override
    public String toString() {
        return String.format("PS/2Interface [Status: %02X, Data: %02X, Queue: %d]", 
                           statusRegister, dataRegister, keyQueue.size());
    }
    
    // Public accessors
    public boolean hasData() {
        return !keyQueue.isEmpty();
    }
    
    public int getQueueSize() {
        return keyQueue.size();
    }
    
    /**
     * Simulate a key press from the console input
     * This method is called by the Simulator to inject console input as PS/2 scan codes
     */
    public void simulateKeyFromConsole(int scanCode) {
        keyQueue.offer(scanCode);
        updateDataReadyStatus();
        notifyListeners();
        logger.info("Console key simulated as scan code: {}", String.format("%02X", scanCode));
    }
    
    /**
     * Simulate a character input from console with proper PS/2 protocol
     * Generates timed scan code sequences including shift handling
     */
    public void simulateCharFromConsole(char ch) {
        int scanCode = getBasicScanCode(ch);
        if (scanCode > 0) {
            if (Character.isLetter(ch) && Character.isUpperCase(ch)) {
                // Generate proper PS/2 shift sequence for uppercase letters
                int[] sequence = {0x12, scanCode, 0xF0, scanCode, 0xF0, 0x12};
                schedulePS2Sequence(sequence, ch + " (uppercase)");
            } else if (isShiftedPunctuation(ch)) {
                // Generate shift sequence for shifted punctuation
                int[] sequence = {0x12, scanCode, 0xF0, scanCode, 0xF0, 0x12};
                schedulePS2Sequence(sequence, ch + " (shifted)");
            } else {
                // For lowercase and non-shifted characters, just press and release
                int[] sequence = {scanCode, 0xF0, scanCode};
                schedulePS2Sequence(sequence, ch + " (normal)");
            }
        }
    }
    
    /**
     * Schedule a sequence of PS/2 scan codes with proper timing
     * Uses background thread to send codes with 2ms intervals
     */
    private void schedulePS2Sequence(int[] scanCodes, String description) {
        Thread sequenceThread = new Thread(() -> {
            try {
                for (int i = 0; i < scanCodes.length; i++) {
                    int scanCode = scanCodes[i];
                    
                    // Send the scan code
                    synchronized (this) {
                        keyQueue.offer(scanCode);
                        updateDataReadyStatus();
                        notifyListeners();
                    }
                    
                    logger.info("PS/2 sequence {}: sent scan code {} ({}/{})", 
                               description, String.format("%02X", scanCode), i + 1, scanCodes.length);
                    
                    // Wait 5ms before next scan code (except for last one)
                    if (i < scanCodes.length - 1) {
                        Thread.sleep(5);
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("PS/2 sequence interrupted: {}", description);
                Thread.currentThread().interrupt();
            }
        });
        
        sequenceThread.setDaemon(true);
        sequenceThread.start();
    }
    
    private boolean isShiftedPunctuation(char ch) {
        switch (ch) {
            case ':':    // Shift + ;
            case '!':    // Shift + 1
            case '@':    // Shift + 2
            case '#':    // Shift + 3
            case '$':    // Shift + 4
            case '%':    // Shift + 5
            case '^':    // Shift + 6
            case '&':    // Shift + 7
            case '*':    // Shift + 8
            case '(':    // Shift + 9
            case ')':    // Shift + 0
            case '_':    // Shift + -
            case '+':    // Shift + =
            case '{':    // Shift + [
            case '}':    // Shift + ]
            case '|':    // Shift + \
            case '"':    // Shift + '
            case '<':    // Shift + ,
            case '>':    // Shift + .
            case '?':    // Shift + /
            case '~':    // Shift + `
                return true;
            default:
                return false;
        }
    }
    
    private int getBasicScanCode(char ch) {
        switch (Character.toLowerCase(ch)) {
            case 'a': return 0x1C;
            case 'b': return 0x32;
            case 'c': return 0x21;
            case 'd': return 0x23;
            case 'e': return 0x24;
            case 'f': return 0x2B;
            case 'g': return 0x34;
            case 'h': return 0x33;
            case 'i': return 0x43;
            case 'j': return 0x3B;
            case 'k': return 0x42;
            case 'l': return 0x4B;
            case 'm': return 0x3A;
            case 'n': return 0x31;
            case 'o': return 0x44;
            case 'p': return 0x4D;
            case 'q': return 0x15;
            case 'r': return 0x2D;
            case 's': return 0x1B;
            case 't': return 0x2C;
            case 'u': return 0x3C;
            case 'v': return 0x2A;
            case 'w': return 0x1D;
            case 'x': return 0x22;
            case 'y': return 0x35;
            case 'z': return 0x1A;
            case '0': return 0x45;
            case '1': return 0x16;
            case '2': return 0x1E;
            case '3': return 0x26;
            case '4': return 0x25;
            case '5': return 0x2E;
            case '6': return 0x36;
            case '7': return 0x3D;
            case '8': return 0x3E;
            case '9': return 0x46;
            case ' ': return 0x29;
            case '.': return 0x49;       // Period
            case ',': return 0x41;       // Comma
            case ';': return 0x4C;       // Semicolon
            case ':': return 0x4C;       // Colon (shifted semicolon)
            case '/': return 0x4A;       // Forward slash
            case '\'': return 0x52;      // Apostrophe
            case '[': return 0x54;       // Left bracket
            case ']': return 0x5B;       // Right bracket
            case '\\': return 0x5D;      // Backslash
            case '-': return 0x4E;       // Minus/dash
            case '=': return 0x55;       // Equals
            // Shifted punctuation (map to base key scan codes)
            case '!': return 0x16;       // Shift+1
            case '@': return 0x1E;       // Shift+2
            case '#': return 0x26;       // Shift+3
            case '$': return 0x25;       // Shift+4
            case '%': return 0x2E;       // Shift+5
            case '^': return 0x36;       // Shift+6
            case '&': return 0x3D;       // Shift+7
            case '*': return 0x3E;       // Shift+8
            case '(': return 0x46;       // Shift+9
            case ')': return 0x45;       // Shift+0
            case '_': return 0x4E;       // Shift+-
            case '+': return 0x55;       // Shift+=
            case '{': return 0x54;       // Shift+[
            case '}': return 0x5B;       // Shift+]
            case '|': return 0x5D;       // Shift+\
            case '"': return 0x52;       // Shift+'
            case '<': return 0x41;       // Shift+,
            case '>': return 0x49;       // Shift+.
            case '?': return 0x4A;       // Shift+/
            case '\r': case '\n': return 0x5A;
            case '\u001B': return 0x76; // ESC
            case '\b': return 0x66;      // Backspace
            case '\t': return 0x0D;      // Tab
            default: return 0;
        }
    }
}