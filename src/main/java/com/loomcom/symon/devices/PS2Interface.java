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
 * Memory mapped at $4070-$407F (16 bytes - full VIA register set):
 * $4070 - VIA Port B (output)
 * $4071 - VIA Port A (PS/2 data input from shift registers)
 * $4072 - Data Direction Register B
 * $4073 - Data Direction Register A
 * $4074-$407E - VIA timers, shift register, control registers
 * $407F - Port A Output (no handshake)
 *
 * Simulates PS/2 keyboard interface through VIA + 74HC595 shift registers.
 */
public class PS2Interface extends Device implements KeyListener {
    
    private static final Logger logger = LoggerFactory.getLogger(PS2Interface.class.getName());
    
    // Register offsets (matching VIA 6522 hardware layout)
    private static final int REG_PORTB = 0x00;    // $4070 - VIA Port B (output)
    private static final int REG_PORTA = 0x01;    // $4071 - VIA Port A (PS/2 data input)
    private static final int REG_DDRB = 0x02;     // $4072 - Data Direction Register B
    private static final int REG_DDRA = 0x03;     // $4073 - Data Direction Register A
    private static final int REG_T1CL = 0x04;     // $4074 - Timer 1 Counter Low
    private static final int REG_T1CH = 0x05;     // $4075 - Timer 1 Counter High
    private static final int REG_T1LL = 0x06;     // $4076 - Timer 1 Latch Low
    private static final int REG_T1LH = 0x07;     // $4077 - Timer 1 Latch High
    private static final int REG_T2CL = 0x08;     // $4078 - Timer 2 Counter Low
    private static final int REG_T2CH = 0x09;     // $4079 - Timer 2 Counter High
    private static final int REG_SR = 0x0A;       // $407A - Shift Register
    private static final int REG_ACR = 0x0B;      // $407B - Auxiliary Control Register
    private static final int REG_PCR = 0x0C;      // $407C - Peripheral Control Register
    private static final int REG_IFR = 0x0D;      // $407D - Interrupt Flag Register
    private static final int REG_IER = 0x0E;      // $407E - Interrupt Enable Register
    private static final int REG_ORA_NH = 0x0F;   // $407F - Port A Output (no handshake)
    
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
        SCAN_CODE_MAP[KeyEvent.VK_DELETE] = 0x71;        // Delete key
        SCAN_CODE_MAP[KeyEvent.VK_TAB] = 0x0D;
        SCAN_CODE_MAP[KeyEvent.VK_SHIFT] = 0x12;
        SCAN_CODE_MAP[KeyEvent.VK_CONTROL] = 0x14;
        SCAN_CODE_MAP[KeyEvent.VK_ALT] = 0x11;
        SCAN_CODE_MAP[KeyEvent.VK_CAPS_LOCK] = 0x58;

        // Punctuation keys
        SCAN_CODE_MAP[KeyEvent.VK_COMMA] = 0x41;         // , <
        SCAN_CODE_MAP[KeyEvent.VK_PERIOD] = 0x49;        // . >
        SCAN_CODE_MAP[KeyEvent.VK_SLASH] = 0x4A;         // / ?
        SCAN_CODE_MAP[KeyEvent.VK_SEMICOLON] = 0x4C;     // ; :
        SCAN_CODE_MAP[KeyEvent.VK_QUOTE] = 0x52;         // ' "
        SCAN_CODE_MAP[KeyEvent.VK_OPEN_BRACKET] = 0x54;  // [ {
        SCAN_CODE_MAP[KeyEvent.VK_CLOSE_BRACKET] = 0x5B; // ] }
        SCAN_CODE_MAP[KeyEvent.VK_BACK_SLASH] = 0x5D;    // \ |
        SCAN_CODE_MAP[KeyEvent.VK_MINUS] = 0x4E;         // - _
        SCAN_CODE_MAP[KeyEvent.VK_EQUALS] = 0x55;        // = +
        SCAN_CODE_MAP[KeyEvent.VK_BACK_QUOTE] = 0x0E;    // ` ~
    }
    
    // VIA register state
    private int portARegister = 0;  // PS/2 data appears here (bit-reversed)
    private int portBRegister = 0;  // Output port
    private int ddrARegister = 0;   // Data direction A
    private int ddrBRegister = 0;   // Data direction B
    private int t1clRegister = 0;   // Timer 1 Counter Low
    private int t1chRegister = 0;   // Timer 1 Counter High
    private int t1llRegister = 0;   // Timer 1 Latch Low
    private int t1lhRegister = 0;   // Timer 1 Latch High
    private int t2clRegister = 0;   // Timer 2 Counter Low
    private int t2chRegister = 0;   // Timer 2 Counter High
    private int srRegister = 0;     // Shift Register
    private int acrRegister = 0;    // Auxiliary Control Register
    private int pcrRegister = 0;    // Peripheral Control Register (CA1 interrupt config)
    private int ifrRegister = 0;    // Interrupt Flag Register
    private int ierRegister = 0;    // Interrupt Enable Register
    private boolean interrupt = false;
    
    // Keyboard input queue
    private final BlockingQueue<Integer> keyQueue = new LinkedBlockingQueue<>();
    
    public PS2Interface(int startAddress) throws MemoryRangeException {
        super(startAddress, startAddress + 0x0F, "PS/2 VIA Interface");
        logger.info("PS/2 VIA Interface initialized at {}-{}", String.format("%04X", startAddress), String.format("%04X", startAddress + 0x0F));
    }
    
    @Override
    public void write(int address, int data) throws MemoryAccessException {
        logger.info("VIA WRITE: Addr={} Data={}", String.format("%02X", address), String.format("%02X", data & 0xFF));

        switch (address) {
            case REG_PORTB:
                portBRegister = data & 0xFF;
                logger.debug("VIA Port B write: {}", String.format("%02X", portBRegister));
                break;

            case REG_PORTA:
                // Port A is typically input for PS/2 data, but allow writes for completeness
                logger.debug("VIA Port A write: {} (typically input only)", String.format("%02X", data & 0xFF));
                break;

            case REG_DDRB:
                ddrBRegister = data & 0xFF;
                logger.debug("VIA DDR B write: {}", String.format("%02X", ddrBRegister));
                break;

            case REG_DDRA:
                ddrARegister = data & 0xFF;
                logger.debug("VIA DDR A write: {}", String.format("%02X", ddrARegister));
                break;

            case REG_T1CL:
                t1clRegister = data & 0xFF;
                logger.debug("VIA T1CL write: {}", String.format("%02X", t1clRegister));
                break;

            case REG_T1CH:
                t1chRegister = data & 0xFF;
                logger.debug("VIA T1CH write: {}", String.format("%02X", t1chRegister));
                break;

            case REG_T1LL:
                t1llRegister = data & 0xFF;
                logger.debug("VIA T1LL write: {}", String.format("%02X", t1llRegister));
                break;

            case REG_T1LH:
                t1lhRegister = data & 0xFF;
                logger.debug("VIA T1LH write: {}", String.format("%02X", t1lhRegister));
                break;

            case REG_T2CL:
                t2clRegister = data & 0xFF;
                logger.debug("VIA T2CL write: {}", String.format("%02X", t2clRegister));
                break;

            case REG_T2CH:
                t2chRegister = data & 0xFF;
                logger.debug("VIA T2CH write: {}", String.format("%02X", t2chRegister));
                break;

            case REG_SR:
                srRegister = data & 0xFF;
                logger.debug("VIA SR write: {}", String.format("%02X", srRegister));
                break;

            case REG_ACR:
                acrRegister = data & 0xFF;
                logger.debug("VIA ACR write: {}", String.format("%02X", acrRegister));
                break;

            case REG_PCR:
                pcrRegister = data & 0xFF;
                logger.debug("VIA PCR write: {} (CA1 interrupt config)", String.format("%02X", pcrRegister));
                break;

            case REG_IFR:
                // Writing to IFR clears interrupt flags
                ifrRegister &= ~data;
                logger.debug("VIA IFR clear: {}, now: {}", String.format("%02X", data), String.format("%02X", ifrRegister));
                break;

            case REG_IER:
                ierRegister = data & 0xFF;
                logger.debug("VIA IER write: {} (interrupt enable)", String.format("%02X", ierRegister));
                break;

            case REG_ORA_NH:
                logger.debug("VIA ORA_NH write: {} (no handshake)", String.format("%02X", data & 0xFF));
                break;

            default:
                logger.warn("Write to invalid VIA register at {}", String.format("%02X", address));
                break;
        }

        notifyListeners();
    }
    
    @Override
    public int read(int address, boolean cpuAccess) throws MemoryAccessException {
        switch (address) {
            case REG_PORTB:
                logger.info("VIA Port B READ: {}", String.format("%02X", portBRegister));
                return portBRegister;

            case REG_PORTA:
                // Port A contains PS/2 data from shift register
                if (!keyQueue.isEmpty() && cpuAccess) {
                    int scanCode = keyQueue.poll();
                    // Use scan code directly (hardware wiring corrected)
                    portARegister = scanCode;

                    // Clear interrupt when data is read
                    interrupt = false;

                    // Schedule new interrupt if more data available
                    if (!keyQueue.isEmpty()) {
                        scheduleNextInterrupt();
                    }

                    logger.info("VIA Port A READ: Scan code={}",
                               String.format("%02X", portARegister));
                }
                return portARegister;

            case REG_DDRB:
                logger.debug("VIA DDR B READ: {}", String.format("%02X", ddrBRegister));
                return ddrBRegister;

            case REG_DDRA:
                logger.debug("VIA DDR A READ: {}", String.format("%02X", ddrARegister));
                return ddrARegister;

            case REG_T1CL:
                logger.debug("VIA T1CL read: {}", String.format("%02X", t1clRegister));
                return t1clRegister;

            case REG_T1CH:
                logger.debug("VIA T1CH read: {}", String.format("%02X", t1chRegister));
                return t1chRegister;

            case REG_T1LL:
                logger.debug("VIA T1LL read: {}", String.format("%02X", t1llRegister));
                return t1llRegister;

            case REG_T1LH:
                logger.debug("VIA T1LH read: {}", String.format("%02X", t1lhRegister));
                return t1lhRegister;

            case REG_T2CL:
                logger.debug("VIA T2CL read: {}", String.format("%02X", t2clRegister));
                return t2clRegister;

            case REG_T2CH:
                logger.debug("VIA T2CH read: {}", String.format("%02X", t2chRegister));
                return t2chRegister;

            case REG_SR:
                logger.debug("VIA SR read: {}", String.format("%02X", srRegister));
                return srRegister;

            case REG_ACR:
                logger.debug("VIA ACR read: {}", String.format("%02X", acrRegister));
                return acrRegister;

            case REG_PCR:
                logger.debug("VIA PCR read: {}", String.format("%02X", pcrRegister));
                return pcrRegister;

            case REG_IFR:
                // Set CA1 flag if interrupt is pending
                int currentIFR = ifrRegister;
                if (interrupt) {
                    currentIFR |= 0x02; // CA1 interrupt flag
                }
                logger.debug("VIA IFR read: {} (interrupt={})", String.format("%02X", currentIFR), interrupt);
                return currentIFR;

            case REG_IER:
                logger.debug("VIA IER read: {}", String.format("%02X", ierRegister));
                return ierRegister;

            case REG_ORA_NH:
                logger.debug("VIA ORA_NH read: {}", String.format("%02X", portARegister));
                return portARegister;

            default:
                logger.warn("Read from invalid VIA register at {}", String.format("%02X", address));
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
            ifrRegister |= STATUS_INHIBIT;
            logger.debug("PS/2 clock inhibited");
        } else {
            ifrRegister &= ~STATUS_INHIBIT;
            logger.debug("PS/2 clock enabled");
        }
    }
    
    private void updateDataReadyStatus() {
        if (!keyQueue.isEmpty()) {
            // Trigger interrupt when data becomes available (CA1 interrupt simulation)
            if (!interrupt) {
                interrupt = true;
                getBus().assertIrq();
                logger.debug("PS/2 CA1 interrupt asserted - data available");
            }
        } else {
            interrupt = false;
            logger.debug("PS/2 interrupt cleared - no data available");
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
        logger.info("KEY EVENT PRESSED: {} (keyCode={})", KeyEvent.getKeyText(e.getKeyCode()), e.getKeyCode());
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
        logger.info("KEY EVENT RELEASED: {} (keyCode={})", KeyEvent.getKeyText(e.getKeyCode()), e.getKeyCode());

        // macOS treats Caps Lock as a toggle - it sends keyPressed when turning ON
        // and keyReleased when turning OFF, one event per physical press.
        // Real PS/2 sends make+break for every press. To simulate proper PS/2,
        // treat the macOS "released" event as another toggle (send make code only).
        if (e.getKeyCode() == KeyEvent.VK_CAPS_LOCK) {
            int scanCode = SCAN_CODE_MAP[e.getKeyCode()];
            keyQueue.offer(scanCode);  // Send make code, not break code
            updateDataReadyStatus();
            notifyListeners();
            logger.info("Caps Lock toggle (macOS workaround): sending make code {}",
                        String.format("%02X", scanCode));
            return;
        }

        int scanCode = SCAN_CODE_MAP[e.getKeyCode()];
        if (scanCode != 0) {
            keyQueue.offer(0xF0); // Break code prefix
            keyQueue.offer(scanCode);
            updateDataReadyStatus();
            notifyListeners();
            logger.info("Key released: {} (scan code: F0 {})",
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
        return String.format("PS/2Interface [PortA: %02X, PortB: %02X, Queue: %d, IRQ: %s]",
                           portARegister, portBRegister, keyQueue.size(), interrupt ? "ASSERTED" : "CLEARED");
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
            case '`': return 0x0E;       // Backtick/grave accent
            case '~': return 0x0E;       // Tilde (shifted backtick)
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