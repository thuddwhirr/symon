package com.loomcom.symon.devices;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test PeripheralController + SpiSDCard SPI protocol, focusing on CMD0 and dummy clock handling.
 */
public class PeripheralControllerSpiSDCardTest {
    private PeripheralController controller;
    private SpiSDCard sdCard;

    // VIA register offsets
    private static final int ORB_IRB = 0x00;
    private static final int ORA_IRA = 0x01;
    private static final int DDRB = 0x02;
    private static final int DDRA = 0x03;

    // Port B pins
    private static final int SPI_MOSI = 0x01; // PB0
    private static final int SPI_MISO = 0x02; // PB1
    private static final int SPI_SCK  = 0x04; // PB2
    // Port A pins
    private static final int SPI_CS0  = 0x01; // PA0

    @BeforeEach
    public void setUp() throws Exception {
        controller = new PeripheralController(0x4030);
        sdCard = new SpiSDCard();
        controller.registerSpiDevice(0, sdCard);
        // Set DDR: PB0 (MOSI) and PB2 (SCK) output, PB1 (MISO) input
        controller.write(DDRB, SPI_MOSI | SPI_SCK);
        // Set DDRA: all outputs (CS0)
        controller.write(DDRA, 0xFF);
        // Deselect all (CS0 high)
        controller.write(ORA_IRA, 0xFF);
    }

    /**
     * Helper: Select SD card (CS0 low)
     */
    private void selectCard() throws Exception {
        controller.write(ORA_IRA, 0xFE); // CS0 low (active)
    }

    /**
     * Helper: Deselect SD card (CS0 high)
     */
    private void deselectCard() throws Exception {
        controller.write(ORA_IRA, 0xFF); // CS0 high (inactive)
    }

    /**
     * Helper: SPI bit transfer (host drives MOSI, toggles SCK, reads MISO)
     */
    private int spiTransferBit(int mosiBit) throws Exception {
        // Set MOSI (PB0), SCK low
        int pb = (mosiBit != 0 ? SPI_MOSI : 0);
        controller.write(ORB_IRB, pb); // SCK low
        // SCK rising edge
        controller.write(ORB_IRB, pb | SPI_SCK);
        // Read MISO after rising edge
        int portB = controller.read(ORB_IRB, true);
        int miso = (portB & SPI_MISO) != 0 ? 1 : 0;
        // SCK falling edge
        controller.write(ORB_IRB, pb);
        return miso;
    }

    /**
     * Helper: SPI byte transfer (MSB first)
     */
    private int spiTransferByte(int byteOut) throws Exception {
        int result = 0;
        for (int i = 7; i >= 0; i--) {
            int mosi = (byteOut >> i) & 1;
            int miso = spiTransferBit(mosi);
            result = (result << 1) | miso;
        }
        return result;
    }

    @Test
    public void testCmd0ResponseWithDummyClocks() throws Exception {
        selectCard();
        // Send CMD0 (0x40), arg=0, CRC=0x95 (arbitrary valid CRC for CMD0)
        int[] cmd0 = {0x40, 0x00, 0x00, 0x00, 0x00, 0x95};
        for (int b : cmd0) {
            spiTransferByte(b);
        }
        // Now, clock dummy bytes until we get a non-0xFF response (should be 0x01)
        int response = 0xFF;
        int clocks = 0;
        while (response == 0xFF && clocks < 16) {
            response = spiTransferByte(0xFF);
            clocks++;
        }
        assertEquals(0x01, response, "CMD0 R1 response should be 0x01 after dummy clocks");
        // Deselect and reselect, ensure no stale bits
        deselectCard();
        selectCard();
        // Send CMD0 again
        for (int b : cmd0) {
            spiTransferByte(b);
        }
        response = 0xFF;
        clocks = 0;
        while (response == 0xFF && clocks < 16) {
            response = spiTransferByte(0xFF);
            clocks++;
        }
        assertEquals(0x01, response, "CMD0 R1 response should be 0x01 after reselect and dummy clocks");
    }
}
