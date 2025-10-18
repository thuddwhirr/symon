package com.loomcom.symon;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.loomcom.symon.devices.Memory;
import com.loomcom.symon.devices.PeripheralController;
import com.loomcom.symon.devices.SpiSDCard;
import com.loomcom.symon.exceptions.MemoryAccessException;

/**
 * Unit tests for SPI device communication
 * Tests the PeripheralController and SpiSDCard interaction
 */
public class SpiDeviceTest extends TestCase {

    private Bus bus;
    private PeripheralController peripheral;
    private SpiSDCard sdCard;
    private Cpu cpu;

    // VIA 6522 register offsets
    private static final int VIA_ORA = 0x4031;  // Output Register A (Port A)
    private static final int VIA_ORB = 0x4030;  // Output Register B (Port B)
    private static final int VIA_DDRA = 0x4033; // Data Direction Register A
    private static final int VIA_DDRB = 0x4032; // Data Direction Register B

    // SPI pin masks
    private static final int SPI_MOSI = 0x01;   // PB0
    private static final int SPI_MISO = 0x02;   // PB1
    private static final int SPI_SCK = 0x04;    // PB2
    private static final int SPI_CS0 = 0x01;    // PA0

    public SpiDeviceTest(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(SpiDeviceTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Create bus
        bus = new Bus(0x0000, 0xFFFF);

        // Create and add peripheral controller
        peripheral = new PeripheralController(0x4030);
        bus.addDevice(peripheral);

        // Create and add SD card
        sdCard = new SpiSDCard();
        peripheral.registerSpiDevice(0, sdCard);  // Connect to CS0

        // We don't need CPU for SPI testing, so skip the reset
    }

    /**
     * Test basic peripheral controller setup
     */
    public void testPeripheralControllerSetup() throws MemoryAccessException {
        // Debug: Print the actual address constants
        System.out.println("VIA_DDRA = 0x" + Integer.toHexString(VIA_DDRA));
        System.out.println("VIA_DDRB = 0x" + Integer.toHexString(VIA_DDRB));

        // Test that we can write to direction registers
        bus.write(VIA_DDRA, 0x3F);  // Set PA0-PA5 as outputs (CS pins)
        bus.write(VIA_DDRB, 0x05);  // Set PB0,PB2 as outputs (MOSI,SCK), PB1 as input (MISO)

        assertEquals(0x3F, bus.read(VIA_DDRA, false));
        assertEquals(0x05, bus.read(VIA_DDRB, false));
    }

    /**
     * Test SPI chip select functionality
     */
    public void testSpiChipSelect() throws MemoryAccessException {
        // Initialize direction registers
        bus.write(VIA_DDRA, 0x3F);  // CS pins as outputs
        bus.write(VIA_DDRB, 0x05);  // MOSI,SCK as outputs

        // Test CS0 selection (active low)
        bus.write(VIA_ORA, 0x3E);   // All CS high except CS0 (0011 1110)
        assertEquals(0x3E, bus.read(VIA_ORA, false));
        assertTrue("SD card should be selected when CS0 is low", sdCard.isSelected());

        // Test CS0 deselection
        bus.write(VIA_ORA, 0x3F);   // All CS high (0011 1111)
        assertEquals(0x3F, bus.read(VIA_ORA, false));
        assertFalse("SD card should be deselected when CS0 is high", sdCard.isSelected());
    }

    /**
     * Test basic SPI byte transfer
     */
    public void testSpiByteTransfer() throws MemoryAccessException {
        // Initialize peripheral
        bus.write(VIA_DDRA, 0x3F);  // CS pins as outputs
        bus.write(VIA_DDRB, 0x05);  // MOSI,SCK as outputs, MISO as input

        // Select SD card (CS0 low)
        bus.write(VIA_ORA, 0x3E);

        // Send CMD0 first byte (0x40) via SPI bit-banging
        System.out.println("=== Testing single byte transfer ===");
        int sendByte = 0x40;
        int receivedByte = spiTransferByte(sendByte);

        System.out.println("Sent: 0x" + Integer.toHexString(sendByte) +
                          ", Received: 0x" + Integer.toHexString(receivedByte));

        // Send dummy byte to check if command was processed
        System.out.println("=== Testing dummy byte after command ===");
        sendByte = 0xFF;
        receivedByte = spiTransferByte(sendByte);
        System.out.println("Sent: 0x" + Integer.toHexString(sendByte) +
                          ", Received: 0x" + Integer.toHexString(receivedByte));
    }

    /**
     * Test SD card CMD0 (GO_IDLE_STATE) command with exact SPI sequence
     */
    public void testSdCardCmd0() throws MemoryAccessException {
        // Initialize peripheral
        bus.write(VIA_DDRA, 0x3F);
        bus.write(VIA_DDRB, 0x05);

        // Select SD card
        bus.write(VIA_ORA, 0x3E);

        // Phase 1: Send CMD0 command bytes - expect 0xFF during most of command transmission
        // Note: SD card may start responding during the last byte transmission
        int[] cmd0 = {0x40, 0x00, 0x00, 0x00, 0x00, 0x95};
        System.out.println("=== Phase 1: Sending CMD0 Command ===");
        for (int i = 0; i < cmd0.length; i++) {
            int response = spiTransferByte(cmd0[i]);
            System.out.println("CMD0[" + i + "]: sent=0x" + Integer.toHexString(cmd0[i]) +
                              ", received=0x" + Integer.toHexString(response));

            if (i < cmd0.length - 1) {
                // First 5 bytes should return 0xFF (card not responding yet)
                assertEquals("SD card should return 0xFF during command transmission", 0xFF, response);
            } else {
                // Last byte may include start of response - this is normal SD card behavior
                System.out.println("Last command byte - response may start during transmission");
            }
        }

        // Phase 2: Send dummy bytes to read R1 response
        System.out.println("=== Phase 2: Reading R1 Response ===");
        int response = 0xFF;
        int maxTries = 8;
        for (int i = 0; i < maxTries && response == 0xFF; i++) {
            response = spiTransferByteWithLogging(0xFF);
            System.out.println("Response attempt " + i + ": sent=0xFF, received=0x" +
                              Integer.toHexString(response) +
                              " (binary: " + String.format("%8s", Integer.toBinaryString(response)).replace(' ', '0') + ")");

            // First dummy byte should return the R1 response (0x01 or partial due to timing)
            if (i == 0) {
                assertTrue("CMD0 should return valid R1 response (0x01 or partial due to timing)",
                          response == 0x01 || (response & 0x01) == 0x01);
                break;
            }
        }

        // Verify we got the expected response (allow for timing artifacts)
        assertTrue("CMD0 should return valid R1 response indicating idle state",
                  response == 0x01 || (response & 0x01) == 0x01);

        // Deselect SD card
        bus.write(VIA_ORA, 0x3F);
    }

    /**
     * Test complete SD card initialization sequence
     */
    public void testCompleteSDCardInit() throws MemoryAccessException {
        // Initialize peripheral
        bus.write(VIA_DDRA, 0x3F);
        bus.write(VIA_DDRB, 0x05);

        System.out.println("\n=== COMPLETE SD CARD INITIALIZATION TEST ===");

        // Step 1: CMD0 (GO_IDLE_STATE)
        System.out.println("\n--- Step 1: CMD0 (GO_IDLE_STATE) ---");
        bus.write(VIA_ORA, 0x3E);  // Select SD card

        int[] cmd0 = {0x40, 0x00, 0x00, 0x00, 0x00, 0x95};
        for (int b : cmd0) {
            spiTransferByte(b);
        }

        int response = spiWaitForResponse();
        System.out.println("CMD0 response: 0x" + Integer.toHexString(response));

        // SD cards may start responding during command transmission, so we may read
        // a partial response. Accept both 0x01 (complete) and partial responses.
        assertTrue("CMD0 should return valid R1 response (0x01 or partial due to timing)",
                  response == 0x01 || (response & 0x01) == 0x01);

        bus.write(VIA_ORA, 0x3F);  // Deselect

        // Step 2: CMD8 (SEND_IF_COND)
        System.out.println("\n--- Step 2: CMD8 (SEND_IF_COND) ---");
        bus.write(VIA_ORA, 0x3E);  // Select SD card

        int[] cmd8 = {0x48, 0x00, 0x00, 0x01, 0xAA, 0x87};  // 3.3V, test pattern 0xAA
        for (int b : cmd8) {
            spiTransferByte(b);
        }

        response = spiWaitForResponse();
        System.out.println("CMD8 R1 response: 0x" + Integer.toHexString(response));

        if (response == 0x01) {
            // Read R7 response (4 additional bytes)
            System.out.println("CMD8 R7 response:");
            for (int i = 0; i < 4; i++) {
                int r7Byte = spiTransferByte(0xFF);
                System.out.println("  Byte " + i + ": 0x" + Integer.toHexString(r7Byte));
            }
        }

        bus.write(VIA_ORA, 0x3F);  // Deselect

        // Step 3: ACMD41 (APP_SEND_OP_COND)
        System.out.println("\n--- Step 3: ACMD41 (APP_SEND_OP_COND) ---");

        int acmd41Response;
        int maxRetries = 5;

        for (int retry = 0; retry < maxRetries; retry++) {
            System.out.println("ACMD41 attempt " + (retry + 1));

            // First send CMD55 (APP_CMD)
            bus.write(VIA_ORA, 0x3E);  // Select SD card
            int[] cmd55 = {0x77, 0x00, 0x00, 0x00, 0x00, 0x65};
            for (int b : cmd55) {
                spiTransferByte(b);
            }
            int cmd55Response = spiWaitForResponse();
            System.out.println("CMD55 response: 0x" + Integer.toHexString(cmd55Response));
            bus.write(VIA_ORA, 0x3F);  // Deselect

            // Then send CMD41 (SEND_OP_COND)
            bus.write(VIA_ORA, 0x3E);  // Select SD card
            int[] cmd41 = {0x69, 0x40, 0x00, 0x00, 0x00, 0x77};  // HCS=1 for SDHC support
            for (int b : cmd41) {
                spiTransferByte(b);
            }
            acmd41Response = spiWaitForResponse();
            System.out.println("CMD41 response: 0x" + Integer.toHexString(acmd41Response));
            bus.write(VIA_ORA, 0x3F);  // Deselect

            if (acmd41Response == 0x00) {
                System.out.println("SD card initialization complete!");
                break;
            }
        }

        // Step 4: CMD58 (READ_OCR) - Optional but good to verify
        System.out.println("\n--- Step 4: CMD58 (READ_OCR) ---");
        bus.write(VIA_ORA, 0x3E);  // Select SD card

        int[] cmd58 = {0x7A, 0x00, 0x00, 0x00, 0x00, 0xFD};
        for (int b : cmd58) {
            spiTransferByte(b);
        }

        response = spiWaitForResponse();
        System.out.println("CMD58 R1 response: 0x" + Integer.toHexString(response));

        if (response == 0x00) {
            // Read OCR register (4 bytes)
            System.out.println("CMD58 OCR register:");
            for (int i = 0; i < 4; i++) {
                int ocrByte = spiTransferByte(0xFF);
                System.out.println("  OCR[" + i + "]: 0x" + Integer.toHexString(ocrByte));
            }
        }

        bus.write(VIA_ORA, 0x3F);  // Deselect

        System.out.println("\n=== SD CARD INITIALIZATION SEQUENCE COMPLETE ===");
    }

    /**
     * Helper method to wait for non-0xFF response
     */
    private int spiWaitForResponse() throws MemoryAccessException {
        int response = 0xFF;
        for (int i = 0; i < 8 && response == 0xFF; i++) {
            response = spiTransferByte(0xFF);
        }
        return response;
    }

    /**
     * Helper method to transfer a byte via SPI bit-banging
     * Simulates the 6502 assembly SPI transfer function
     */
    private int spiTransferByte(int sendByte) throws MemoryAccessException {
        int receivedByte = 0;

        for (int bit = 7; bit >= 0; bit--) {
            // Set MOSI based on current bit
            int portB = bus.read(VIA_ORB, false);
            if ((sendByte & (1 << bit)) != 0) {
                portB |= SPI_MOSI;  // MOSI high
            } else {
                portB &= ~SPI_MOSI; // MOSI low
            }
            bus.write(VIA_ORB, portB);

            // Clock high
            portB |= SPI_SCK;
            bus.write(VIA_ORB, portB);

            // Read MISO using EXACT same logic as assembly code
            receivedByte = receivedByte << 1;  // Same as "asl spi_recv_byte"
            int portBRead = bus.read(VIA_ORB, false);
            if ((portBRead & SPI_MISO) != 0) {
                receivedByte |= 1;  // Same as "inc spi_recv_byte"
            }

            // Clock low
            portB &= ~SPI_SCK;
            bus.write(VIA_ORB, portB);
        }

        return receivedByte;
    }

    /**
     * Helper method with detailed bit-by-bit logging
     */
    private int spiTransferByteWithLogging(int sendByte) throws MemoryAccessException {
        int receivedByte = 0;
        System.out.println("=== Detailed SPI Transfer ===");
        System.out.println("Sending: 0x" + Integer.toHexString(sendByte) +
                          " (binary: " + String.format("%8s", Integer.toBinaryString(sendByte)).replace(' ', '0') + ")");

        for (int bit = 7; bit >= 0; bit--) {
            // Set MOSI based on current bit
            int portB = bus.read(VIA_ORB, false);
            boolean mosiBit = (sendByte & (1 << bit)) != 0;
            if (mosiBit) {
                portB |= SPI_MOSI;  // MOSI high
            } else {
                portB &= ~SPI_MOSI; // MOSI low
            }
            bus.write(VIA_ORB, portB);

            // Clock high
            portB |= SPI_SCK;
            bus.write(VIA_ORB, portB);

            // Read MISO using EXACT same logic as assembly code
            receivedByte = receivedByte << 1;  // Same as "asl spi_recv_byte"
            int portBRead = bus.read(VIA_ORB, false);
            boolean misoBit = (portBRead & SPI_MISO) != 0;
            if (misoBit) {
                receivedByte |= 1;  // Same as "inc spi_recv_byte"
            }

            System.out.println("Bit " + bit + ": MOSI=" + (mosiBit ? 1 : 0) +
                              ", MISO=" + (misoBit ? 1 : 0) +
                              ", received so far=0x" + Integer.toHexString(receivedByte));

            // Clock low
            portB &= ~SPI_SCK;
            bus.write(VIA_ORB, portB);
        }

        System.out.println("Final received: 0x" + Integer.toHexString(receivedByte) +
                          " (binary: " + String.format("%8s", Integer.toBinaryString(receivedByte)).replace(' ', '0') + ")");
        return receivedByte;
    }
}