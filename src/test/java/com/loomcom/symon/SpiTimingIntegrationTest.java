package com.loomcom.symon;

import com.loomcom.symon.devices.PeripheralController;
import com.loomcom.symon.devices.SpiSDCard;
import com.loomcom.symon.exceptions.MemoryAccessException;
import com.loomcom.symon.exceptions.MemoryRangeException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Integration test to verify SPI timing between PeripheralController and SpiSDCard
 * This test simulates the actual 6502 read/write sequence to catch timing bugs
 * that unit tests miss due to their isolated nature.
 */
public class SpiTimingIntegrationTest {

    private PeripheralController peripheral;
    private SpiSDCard sdCard;

    // VIA 6522 register addresses (relative to base)
    private static final int VIA_ORB = 0x0;   // Output Register B (SPI data)
    private static final int VIA_ORA = 0x1;   // Output Register A (SPI control)
    private static final int VIA_DDRB = 0x2;  // Data Direction Register B
    private static final int VIA_DDRA = 0x3;  // Data Direction Register A

    // SPI pin definitions
    private static final int SPI_MOSI = 0x01;  // PB0
    private static final int SPI_MISO = 0x02;  // PB1
    private static final int SPI_SCK = 0x04;   // PB2
    private static final int SPI_CS0 = 0x01;   // PA0

    @Before
    public void setUp() throws MemoryRangeException, MemoryAccessException {
        peripheral = new PeripheralController(0x4030);
        sdCard = new SpiSDCard();
        peripheral.registerSpiDevice(0, sdCard);

        // Initialize VIA for SPI operation (like the 6502 assembly does)
        peripheral.write(VIA_DDRB, SPI_MOSI | SPI_SCK);  // MOSI and SCK as outputs
        peripheral.write(VIA_DDRA, 0x3F);  // CS pins as outputs
        peripheral.write(VIA_ORA, 0x3F);   // All CS high (deselected)
        peripheral.write(VIA_ORB, 0x00);   // SPI lines low
    }

    /**
     * Test that multiple reads while SCK is high return the same MISO value
     */
    @Test
    public void testMultipleReadsWhileClockHigh() throws MemoryAccessException {
        System.out.println("=== Testing multiple reads while SCK high ===");

        // Send CMD0 to get response ready
        selectSpiDevice(0);
        sendCmd0ViaPeripheralOnly();

        // Start reading response with first bit
        peripheral.write(VIA_ORB, SPI_MOSI);  // MOSI high, SCK low
        peripheral.write(VIA_ORB, SPI_MOSI | SPI_SCK);  // SCK high

        // Read MISO multiple times while SCK is high - should return same value
        int read1 = peripheral.read(VIA_ORB, false) & SPI_MISO;
        int read2 = peripheral.read(VIA_ORB, false) & SPI_MISO;
        int read3 = peripheral.read(VIA_ORB, false) & SPI_MISO;

        System.out.printf("Multiple reads while SCK high: %d, %d, %d\n",
                         read1 != 0 ? 1 : 0, read2 != 0 ? 1 : 0, read3 != 0 ? 1 : 0);

        assertEquals("All reads while SCK high should return same value", read1, read2);
        assertEquals("All reads while SCK high should return same value", read2, read3);

        // SCK low - advance to next bit
        peripheral.write(VIA_ORB, SPI_MOSI);

        deselectSpiDevice();
    }

    /**
     * Simulate the exact 6502 SPI transfer sequence that's failing
     * This should catch the stale MISO bit timing issue
     */
    @Test
    public void testStaleMinoTimingIssue() throws MemoryAccessException {
        System.out.println("=== Testing for stale MISO bit timing issue ===");

        // Step 1: Send CMD0 command using only PeripheralController operations
        selectSpiDevice(0);
        sendCmd0ViaPeripheralOnly();

        // Step 2: Send dummy byte (0xFF) to read response
        // This is where the timing issue occurs
        System.out.println("Sending dummy byte 0xFF to read response...");

        int[] misoValues = new int[8];
        String[] expectedBits = {"0", "0", "0", "0", "0", "0", "0", "1"}; // MSB first for 0x01

        for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
            // Set MOSI high (dummy byte bit)
            peripheral.write(VIA_ORB, SPI_MOSI);

            // Clock high - this triggers the SPI transfer and MISO calculation
            peripheral.write(VIA_ORB, SPI_MOSI | SPI_SCK);

            // Read MISO value while SCK is high (exactly like 6502 assembly does)
            int portBValue = peripheral.read(VIA_ORB, false);
            misoValues[bitIndex] = (portBValue & SPI_MISO) != 0 ? 1 : 0;

            System.out.printf("Bit %d: MOSI=1, SCK=1, MISO=%d (expected %s)\n",
                            bitIndex, misoValues[bitIndex], expectedBits[bitIndex]);

            // Clock low - complete the clock cycle
            peripheral.write(VIA_ORB, SPI_MOSI);
        }

        // Verify the sequence - this should FAIL with current broken implementation
        System.out.println("\n=== Analysis ===");
        StringBuilder actualSequence = new StringBuilder();
        StringBuilder expectedSequence = new StringBuilder();

        for (int i = 0; i < 8; i++) {
            actualSequence.append(misoValues[i]);
            expectedSequence.append(expectedBits[i]);
            if (i < 7) {
                actualSequence.append(",");
                expectedSequence.append(",");
            }
        }

        System.out.println("Expected MSB-first: " + expectedSequence.toString());
        System.out.println("Actual sequence:    " + actualSequence.toString());

        // Check for stale first bit (common failure pattern)
        if (misoValues[0] == 1) {
            System.out.println("❌ STALE BIT DETECTED: First bit is 1 instead of 0");
            System.out.println("   This indicates MISO calculation is happening after return");
        }

        // Check for missing last bit (another common failure pattern)
        if (misoValues[7] == 0) {
            System.out.println("❌ MISSING LAST BIT: Last bit is 0 instead of 1");
            System.out.println("   This indicates timing synchronization issues");
        }

        // CMD0 response should be exactly 0x01 = 0,0,0,0,0,0,0,1 (MSB first)
        String expected = "0,0,0,0,0,0,0,1";

        if (actualSequence.toString().equals(expected)) {
            System.out.println("✓ Correct CMD0 response: " + expected);
        } else {
            fail("Unexpected bit sequence: " + actualSequence.toString() + ", expected: " + expected);
        }

        deselectSpiDevice();
    }

    /**
     * Send CMD0 command (40 00 00 00 00 95) to set up response
     */
    private void sendCmd0Command() throws MemoryAccessException {
        System.out.println("Sending CMD0 command...");
        int[] cmd0 = {0x40, 0x00, 0x00, 0x00, 0x00, 0x95};

        for (int byteIndex = 0; byteIndex < cmd0.length; byteIndex++) {
            sendByte(cmd0[byteIndex]);
            System.out.printf("CMD0[%d]: sent=0x%02X\n", byteIndex, cmd0[byteIndex]);
        }

        System.out.println("CMD0 complete - response should be queued");
    }

    /**
     * Send a single byte via SPI using bit-by-bit operations
     */
    private void sendByte(int byteValue) throws MemoryAccessException {
        for (int bitIndex = 7; bitIndex >= 0; bitIndex--) {  // MSB first
            int bitValue = (byteValue >> bitIndex) & 1;

            // Set MOSI
            int mosiValue = bitValue != 0 ? SPI_MOSI : 0;
            peripheral.write(VIA_ORB, mosiValue);

            // Clock high
            peripheral.write(VIA_ORB, mosiValue | SPI_SCK);

            // Clock low
            peripheral.write(VIA_ORB, mosiValue);
        }
    }

    /**
     * Select SPI device by pulling CS low
     */
    private void selectSpiDevice(int deviceNumber) throws MemoryAccessException {
        int csMask = ~(1 << deviceNumber);  // Clear the CS bit for selected device
        peripheral.write(VIA_ORA, 0x3F & csMask);
        System.out.printf("Selected SPI device %d\n", deviceNumber);
    }

    /**
     * Deselect all SPI devices by setting all CS high
     */
    private void deselectSpiDevice() throws MemoryAccessException {
        peripheral.write(VIA_ORA, 0x3F);  // All CS high
        System.out.println("Deselected all SPI devices");
    }

    /**
     * Send CMD0 command using ONLY PeripheralController read/write operations
     * This exactly mimics what the 6502 assembly code does
     */
    private void sendCmd0ViaPeripheralOnly() throws MemoryAccessException {
        System.out.println("Sending CMD0 command via PeripheralController only...");
        int[] cmd0 = {0x40, 0x00, 0x00, 0x00, 0x00, 0x95};

        for (int byteIndex = 0; byteIndex < cmd0.length; byteIndex++) {
            sendByteViaPeripheralOnly(cmd0[byteIndex]);
            System.out.printf("CMD0[%d]: sent=0x%02X via PeripheralController\\n", byteIndex, cmd0[byteIndex]);
        }

        System.out.println("CMD0 complete via PeripheralController - response should be queued");
    }

    /**
     * Send a single byte via SPI using ONLY PeripheralController operations
     * This exactly mimics the 6502 assembly spi_transfer function
     */
    private void sendByteViaPeripheralOnly(int byteValue) throws MemoryAccessException {
        for (int bitIndex = 7; bitIndex >= 0; bitIndex--) {  // MSB first
            int bitValue = (byteValue >> bitIndex) & 1;

            // Set MOSI bit using PeripheralController write
            int mosiValue = bitValue != 0 ? SPI_MOSI : 0;
            peripheral.write(VIA_ORB, mosiValue);

            // Clock high using PeripheralController write
            peripheral.write(VIA_ORB, mosiValue | SPI_SCK);

            // Clock low using PeripheralController write
            peripheral.write(VIA_ORB, mosiValue);
        }
    }
}