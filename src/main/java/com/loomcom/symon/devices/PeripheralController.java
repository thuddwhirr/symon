/*
 * Copyright (c) 2025 Waffle2e Computer Project
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
 */

package com.loomcom.symon.devices;

import com.loomcom.symon.exceptions.MemoryAccessException;
import com.loomcom.symon.exceptions.MemoryRangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Waffle2e Peripheral Controller
 * VIA 6522-based I2C/SPI interface for external devices
 */
public class PeripheralController extends Device {

    private final static Logger logger = LoggerFactory.getLogger(PeripheralController.class.getName());

    public static final int PERIPHERAL_SIZE = 16;

    // VIA 6522 Register offsets
    private static final int ORB_IRB = 0x00;    // Port B Output/Input Register
    private static final int ORA_IRA = 0x01;    // Port A Output/Input Register
    private static final int DDRB = 0x02;       // Data Direction Register B
    private static final int DDRA = 0x03;       // Data Direction Register A
    private static final int T1CL = 0x04;       // Timer 1 Counter Low
    private static final int T1CH = 0x05;       // Timer 1 Counter High
    private static final int T1LL = 0x06;       // Timer 1 Latch Low
    private static final int T1LH = 0x07;       // Timer 1 Latch High
    private static final int T2CL = 0x08;       // Timer 2 Counter Low
    private static final int T2CH = 0x09;       // Timer 2 Counter High
    private static final int SR = 0x0A;         // Shift Register
    private static final int ACR = 0x0B;        // Auxiliary Control Register
    private static final int PCR = 0x0C;        // Peripheral Control Register
    private static final int IFR = 0x0D;        // Interrupt Flag Register
    private static final int IER = 0x0E;        // Interrupt Enable Register
    private static final int ORA_NH = 0x0F;     // Port A Output (no handshake)

    // Port B pins (SPI)
    private static final int SPI_MOSI = 0x01;   // PB0
    private static final int SPI_MISO = 0x02;   // PB1
    private static final int SPI_SCK = 0x04;    // PB2

    // Port A pins (I2C + SPI CS)
    private static final int SPI_CS0 = 0x01;    // PA0
    private static final int SPI_CS1 = 0x02;    // PA1
    private static final int SPI_CS2 = 0x04;    // PA2
    private static final int SPI_CS3 = 0x08;    // PA3
    private static final int SPI_CS4 = 0x10;    // PA4
    private static final int SPI_CS5 = 0x20;    // PA5
    private static final int I2C_SCL = 0x40;    // PA6
    private static final int I2C_SDA = 0x80;    // PA7

    // All CS bits mask
    private static final int SPI_CS_MASK = SPI_CS0 | SPI_CS1 | SPI_CS2 | SPI_CS3 | SPI_CS4 | SPI_CS5;

    // VIA registers
    private int portB = 0x00;
    private int portA = 0x00;
    private int ddrB = 0x00;
    private int ddrA = 0x00;
    private int t1CounterLow = 0x00;
    private int t1CounterHigh = 0x00;
    private int t1LatchLow = 0x00;
    private int t1LatchHigh = 0x00;
    private int t2CounterLow = 0x00;
    private int t2CounterHigh = 0x00;
    private int shiftRegister = 0x00;
    private int auxControlReg = 0x00;
    private int peripheralControlReg = 0x00;
    private int interruptFlagReg = 0x00;
    private int interruptEnableReg = 0x00;

    // SPI state tracking
    private boolean sckPrevious = false;
    private int selectedDevice = -1;  // -1 = none selected

    // Step counter for SPI communication debugging
    private long spiStepCounter = 0;

    // SPI devices (6 chip selects)
    private final Map<Integer, SpiDevice> spiDevices = new HashMap<>();

    public PeripheralController(int address) throws MemoryRangeException {
        super(address, address + PERIPHERAL_SIZE - 1, "Waffle2e Peripheral Controller");

        logger.info("Peripheral Controller initialized at {}-{}",
                    String.format("%04X", address), String.format("%04X", address + PERIPHERAL_SIZE - 1));

        // Initialize all registers to power-on defaults
        reset();
    }

    /**
     * Register an SPI device on a specific chip select line
     * @param csLine chip select line (0-5)
     * @param device SPI device to register
     */
    public void registerSpiDevice(int csLine, SpiDevice device) {
        if (csLine < 0 || csLine > 5) {
            throw new IllegalArgumentException("SPI CS line must be 0-5");
        }
        spiDevices.put(csLine, device);
        logger.info("Registered SPI device '{}' on CS{}", device.getName(), csLine);
    }

    /**
     * Remove SPI device from chip select line
     * @param csLine chip select line (0-5)
     */
    public void unregisterSpiDevice(int csLine) {
        SpiDevice device = spiDevices.remove(csLine);
        if (device != null) {
            device.deselect();
            logger.info("Unregistered SPI device '{}' from CS{}", device.getName(), csLine);
        }
    }

    @Override
    public void write(int address, int data) throws MemoryAccessException {
        int register = address;  // Bus already provides relative address

        switch (register) {
            case ORB_IRB:
                writePortB(data);
                break;
            case ORA_IRA:
            case ORA_NH:
                writePortA(data);
                break;
            case DDRB:
                ddrB = data & 0xFF;
                break;
            case DDRA:
                ddrA = data & 0xFF;
                break;
            case T1CL:
                t1LatchLow = data & 0xFF;
                break;
            case T1CH:
                t1CounterHigh = data & 0xFF;
                t1CounterLow = t1LatchLow;
                // TODO: Start timer 1 if needed
                break;
            case T1LL:
                t1LatchLow = data & 0xFF;
                break;
            case T1LH:
                t1LatchHigh = data & 0xFF;
                break;
            case T2CL:
                t2CounterLow = data & 0xFF;
                break;
            case T2CH:
                t2CounterHigh = data & 0xFF;
                // TODO: Start timer 2 if needed
                break;
            case SR:
                shiftRegister = data & 0xFF;
                break;
            case ACR:
                auxControlReg = data & 0xFF;
                break;
            case PCR:
                peripheralControlReg = data & 0xFF;
                break;
            case IFR:
                // Writing to IFR clears interrupt flags
                interruptFlagReg &= ~(data & 0x7F);
                break;
            case IER:
                if ((data & 0x80) != 0) {
                    // Set interrupt enable bits
                    interruptEnableReg |= (data & 0x7F);
                } else {
                    // Clear interrupt enable bits
                    interruptEnableReg &= ~(data & 0x7F);
                }
                break;
            default:
                logger.warn("Write to unimplemented Peripheral register 0x{} = 0x{}", String.format("%02X", register), String.format("%02X", data));
        }
    }

    @Override
    public int read(int address, boolean cpuAccess) throws MemoryAccessException {
        int register = address;  // Bus already provides relative address

        switch (register) {
            case ORB_IRB:
                return readPortB();
            case ORA_IRA:
            case ORA_NH:
                return readPortA();
            case DDRB:
                return ddrB;
            case DDRA:
                return ddrA;
            case T1CL:
                // Clear T1 interrupt flag when reading low counter
                interruptFlagReg &= ~0x40;
                return t1CounterLow;
            case T1CH:
                return t1CounterHigh;
            case T1LL:
                return t1LatchLow;
            case T1LH:
                return t1LatchHigh;
            case T2CL:
                // Clear T2 interrupt flag when reading low counter
                interruptFlagReg &= ~0x20;
                return t2CounterLow;
            case T2CH:
                return t2CounterHigh;
            case SR:
                return shiftRegister;
            case ACR:
                return auxControlReg;
            case PCR:
                return peripheralControlReg;
            case IFR:
                return interruptFlagReg | (((interruptFlagReg & interruptEnableReg) != 0) ? 0x80 : 0x00);
            case IER:
                return interruptEnableReg | 0x80;  // Bit 7 always reads as 1
            default:
                logger.warn("Read from unimplemented Peripheral register 0x{}", String.format("%02X", register));
                return 0xFF;
        }
    }

    private void writePortB(int data) {
        int oldPortB = portB;
        // Only update bits configured as outputs in DDRB
        // Input bits (like MISO) preserve their current values
        portB = (portB & ~ddrB) | (data & ddrB);

        // Extract SPI signals for logging (cs from actual portA value)
        int cs = portA & SPI_CS_MASK;
        int clk = (portB & SPI_SCK) != 0 ? 1 : 0;
        int mosi = (portB & SPI_MOSI) != 0 ? 1 : 0;
        int miso = (portB & SPI_MISO) != 0 ? 1 : 0;

        // Log the write operation (DISABLED - too noisy)
        // logger.info(spiStepCounter + ": write portb = 0x" + String.format("%02X", portB) +
        //            ", cs=0x" + String.format("%02X", cs) + ", clk=" + clk + ", mosi=" + mosi + ", miso=" + miso);
        spiStepCounter++;

        // Handle SPI clock edge detection
        boolean sckCurrent = (portB & SPI_SCK) != 0;
        if (!sckPrevious && sckCurrent) {
            // Rising edge of SCK - read MOSI and set MISO
            handleSpiTransfer();
        } else if (sckPrevious && !sckCurrent) {
            // Falling edge of SCK - advance to next bit
            handleSckFallingEdge();
        }
        sckPrevious = sckCurrent;
    }

    private void writePortA(int data) {
        int oldPortA = portA;
        portA = data & 0xFF;

        // Extract SPI signals for logging
        int cs = portA & SPI_CS_MASK;
        int clk = (portB & SPI_SCK) != 0 ? 1 : 0;
        int mosi = (portB & SPI_MOSI) != 0 ? 1 : 0;
        int miso = (portB & SPI_MISO) != 0 ? 1 : 0;

        // Log the write operation (DISABLED - too noisy)
        // logger.info(spiStepCounter + ": write porta = 0x" + String.format("%02X", portA) +
        //            ", cs=0x" + String.format("%02X", cs) + ", clk=" + clk + ", mosi=" + mosi + ", miso=" + miso);
        spiStepCounter++;

        // Handle SPI chip select changes
        handleChipSelectChanges(oldPortA, portA);

        // TODO: Handle I2C operations on PA6/PA7
    }

    private int readPortB() {
        // Return current port B value with MISO input
        int result = portB;  // Keep current MISO state from handleSpiTransfer()

        // If no device selected, MISO floats high
        if (selectedDevice < 0) {
            result |= SPI_MISO;
        }

        // Extract SPI signals for logging
        int cs = portA & SPI_CS_MASK;
        int clk = (result & SPI_SCK) != 0 ? 1 : 0;
        int mosi = (result & SPI_MOSI) != 0 ? 1 : 0;
        int miso = (result & SPI_MISO) != 0 ? 1 : 0;

        // DEBUG: Log when 6502 reads portB during SPI activity (REDUCED LOGGING)
        // if (selectedDevice >= 0 && clk == 1) {
        //     logger.info("6502 READ PortB: step={}, SCK=HIGH, MISO={}, result=0x{}",
        //                 spiStepCounter, miso, String.format("%02X", result));
        // }

        spiStepCounter++;

        return result;
    }

    private int readPortA() {
        // Extract SPI signals for logging
        int cs = portA & SPI_CS_MASK;
        int clk = (portB & SPI_SCK) != 0 ? 1 : 0;
        int mosi = (portB & SPI_MOSI) != 0 ? 1 : 0;
        int miso = (portB & SPI_MISO) != 0 ? 1 : 0;

        // Log the read operation (REDUCED LOGGING)
        // logger.info(spiStepCounter + ": read porta = 0x" + String.format("%02X", portA) +
        //            ", cs=0x" + String.format("%02X", cs) + ", clk=" + clk + ", mosi=" + mosi + ", miso=" + miso);
        spiStepCounter++;

        // Return current port A value
        // TODO: Handle I2C SDA input when configured as input
        return portA;
    }

    private void handleChipSelectChanges(int oldPortA, int newPortA) {
        int oldCS = oldPortA & SPI_CS_MASK;
        int newCS = newPortA & SPI_CS_MASK;

        if (oldCS != newCS) {
            // LOG: High-level chip select events
            logger.info("SPI CHIP SELECT CHANGE: 0x" + String.format("%02X", oldCS) + " -> 0x" + String.format("%02X", newCS) + " (step " + spiStepCounter + ")");

            // Deselect previously selected device
            if (selectedDevice >= 0) {
                logger.info("SPI DESELECT: Device " + selectedDevice);
                SpiDevice device = spiDevices.get(selectedDevice);
                if (device != null) {
                    device.deselect();
                }
                selectedDevice = -1;
            }

            // Find newly selected device (CS lines are active low - exactly one bit should be clear)
            int invertedCS = (~newCS) & SPI_CS_MASK;  // Invert CS bits to find selected device
            if (Integer.bitCount(invertedCS) == 1) {
                for (int i = 0; i < 6; i++) {
                    if ((invertedCS & (1 << i)) != 0) {
                        selectedDevice = i;
                        logger.info("SPI SELECT: Device " + selectedDevice + " (CS line " + i + ")");
                        SpiDevice device = spiDevices.get(i);
                        if (device != null) {
                            device.select();
                        }
                        break;
                    }
                }
            } else if (newCS == SPI_CS_MASK) {
                // All devices deselected (all CS bits high)
                logger.info("SPI DESELECT: All devices (setup mode)");
            } else {
                // Multiple devices selected - this shouldn't happen
                logger.warn("Multiple SPI devices selected simultaneously: CS=0x{:02X}, inverted=0x{:02X}",
                           newCS, invertedCS);
            }
        }
    }

    private void handleSpiTransfer() {
        if (selectedDevice >= 0) {
            SpiDevice device = spiDevices.get(selectedDevice);
            if (device != null && device.isSelected()) {
                // Get MOSI bit
                int mosiValue = (portB & SPI_MOSI) != 0 ? 1 : 0;

                // Perform transfer (device handles bit assembly)
                int misoValue = device.transfer(mosiValue);

                // LOG: High-level SPI transfer events only (not setup clocks) (REDUCED LOGGING)
                // logger.info("SPI TRANSFER: Device " + selectedDevice + " - MOSI=" + mosiValue + " -> MISO=" + misoValue + " (step " + spiStepCounter + ")");

                // Update MISO in port B
                if (misoValue != 0) {
                    portB |= SPI_MISO;
                } else {
                    portB &= ~SPI_MISO;
                }
            }
        }
    }

    private void handleSckFallingEdge() {
        if (selectedDevice >= 0) {
            SpiDevice device = spiDevices.get(selectedDevice);
            if (device != null && device.isSelected()) {
                // Notify device of SCK falling edge
                device.onSckFallingEdge();
                // logger.debug("SPI SCK falling edge: Device " + selectedDevice + " (step " + spiStepCounter + ")");
            }
        }
    }

    public void reset() {
        // Reset all registers to power-on defaults
        portB = 0x00;
        portA = 0x00;
        ddrB = 0x00;
        ddrA = 0x00;
        t1CounterLow = 0xFF;
        t1CounterHigh = 0xFF;
        t1LatchLow = 0xFF;
        t1LatchHigh = 0xFF;
        t2CounterLow = 0xFF;
        t2CounterHigh = 0xFF;
        shiftRegister = 0x00;
        auxControlReg = 0x00;
        peripheralControlReg = 0x00;
        interruptFlagReg = 0x00;
        interruptEnableReg = 0x00;

        // Reset SPI state
        sckPrevious = false;
        selectedDevice = -1;

        // Reset all SPI devices
        for (SpiDevice device : spiDevices.values()) {
            device.reset();
            device.deselect();
        }

        // Reset step counter
        spiStepCounter = 0;

        logger.debug("Peripheral Controller reset completed");
    }

    @Override
    public String toString() {
        return "Waffle2e Peripheral Controller";
    }
}