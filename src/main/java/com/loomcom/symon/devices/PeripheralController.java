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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Waffle2e Peripheral Controller
 * VIA 6522-based I2C/SPI interface for external devices
 *
 * I2C Implementation Notes:
 * The 6502 driver uses open-drain bit-banging via DDR control:
 * - DDR bit = 1 (output): Pin is driven LOW (ORA bit is always 0)
 * - DDR bit = 0 (input): Pin floats HIGH via external pull-up
 * This means we detect line states from DDR, not ORA.
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

    // I2C devices (keyed by 7-bit address)
    private final Map<Integer, I2cDevice> i2cDevices = new HashMap<>();

    // I2C state machine
    private enum I2cState {
        IDLE,               // Waiting for START
        ADDRESS,            // Receiving address byte
        DATA_WRITE,         // Receiving data (master -> slave)
        DATA_READ           // Sending data (slave -> master)
    }

    private I2cState i2cState = I2cState.IDLE;
    private boolean i2cSclPrevious = true;    // Previous SCL state (high = idle)
    private boolean i2cSdaPrevious = true;    // Previous SDA state (high = idle)
    private int i2cBitCount = 0;              // Bits received/sent in current byte (0-8)
    private int i2cShiftReg = 0;              // Shift register for byte assembly
    private I2cDevice i2cActiveDevice = null; // Currently addressed device
    private boolean i2cReadMode = false;      // True if master is reading from slave
    private int i2cReadByte = 0xFF;           // Byte being sent to master
    private boolean i2cSlaveAck = false;      // ACK from slave to send (true=ACK, false=NACK)
    // State machine notes:
    // - i2cBitCount counts from 0 to 8 during data transfer
    // - When bitCount reaches 8, handleI2cByteComplete() is called and bitCount stays at 8
    // - bitCount==8 indicates we're in ACK phase (between 8th data bit and next byte)
    // - On SCL falling edge after ACK, bitCount resets to 0 for next byte

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

    /**
     * Register an I2C device
     * @param device I2C device to register (address is obtained from device)
     */
    public void registerI2cDevice(I2cDevice device) {
        int address = device.getAddress();
        if (address < 0 || address > 0x7F) {
            throw new IllegalArgumentException("I2C address must be 0x00-0x7F");
        }
        i2cDevices.put(address, device);
        logger.info("Registered I2C device '{}' at address 0x{}", device.getName(), String.format("%02X", address));
    }

    /**
     * Remove I2C device
     * @param address 7-bit I2C address
     */
    public void unregisterI2cDevice(int address) {
        I2cDevice device = i2cDevices.remove(address);
        if (device != null) {
            device.reset();
            logger.info("Unregistered I2C device '{}' from address 0x{}", device.getName(), String.format("%02X", address));
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
                int oldDdrA = ddrA;
                ddrA = data & 0xFF;
                // Handle I2C - DDR changes affect line states
                handleI2cDdrChange(oldDdrA, ddrA);
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
        // Start with current port A value
        int result = portA;

        // Handle I2C SDA input when SDA is configured as input (DDR bit = 0)
        if ((ddrA & I2C_SDA) == 0) {
            // SDA is input - return value from I2C bus
            int sdaValue = getI2cSdaValue();
            if (sdaValue != 0) {
                result |= I2C_SDA;   // SDA high
            } else {
                result &= ~I2C_SDA;  // SDA low
            }
        }

        spiStepCounter++;
        return result;
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

    // =========================================================================
    // I2C Implementation
    // =========================================================================

    /**
     * Get current SCL line state from DDR (open-drain: DDR=1 means driven LOW)
     */
    private boolean getI2cSclState() {
        // Open-drain: If DDR bit is set (output), line is driven LOW
        // If DDR bit is clear (input), line floats HIGH via pull-up
        return (ddrA & I2C_SCL) == 0;  // true = HIGH, false = LOW
    }

    /**
     * Get current SDA line state from DDR (open-drain: DDR=1 means driven LOW)
     */
    private boolean getI2cSdaState() {
        return (ddrA & I2C_SDA) == 0;  // true = HIGH, false = LOW
    }

    /**
     * Get SDA value to return when master reads the bus.
     * During slave ACK or data read phases, the slave controls SDA.
     */
    private int getI2cSdaValue() {
        // During ACK phase (bitCount 8 or 9), slave drives ACK
        // bitCount==8: waiting for ACK clock to rise (8th bit done, ACK clock not yet)
        // bitCount==9: ACK clock has risen, master is reading now
        if ((i2cBitCount == 8 || i2cBitCount == 9) && i2cState != I2cState.IDLE) {
            // ACK phase - return slave's ACK (low = ACK, high = NACK)
            int sdaValue = i2cSlaveAck ? 0 : 1;
            logger.debug("I2C getI2cSdaValue: ACK phase (bitCount={}), slaveAck={}, returning {}",
                        i2cBitCount, i2cSlaveAck, sdaValue);
            return sdaValue;
        }

        // During read mode, slave drives SDA with data bits
        if (i2cState == I2cState.DATA_READ && i2cBitCount >= 1 && i2cBitCount <= 8) {
            // Return current bit of read byte (MSB first)
            // Note: bitCount has already been incremented by handleI2cSclRising() before
            // the master reads SDA, so we use bitCount-1 to get the correct bit position
            int bitPos = 7 - (i2cBitCount - 1);
            int bitValue = (i2cReadByte >> bitPos) & 1;
            logger.debug("I2C getI2cSdaValue: DATA_READ bit {}, readByte=0x{}, returning {}",
                        i2cBitCount - 1, String.format("%02X", i2cReadByte), bitValue);
            return bitValue;
        }

        // Otherwise, SDA floats high (pull-up)
        return 1;
    }

    /**
     * Handle DDR changes that affect I2C line states.
     * This is where we detect START, STOP, and clock edges.
     */
    private void handleI2cDdrChange(int oldDdrA, int newDdrA) {
        // Calculate line states (open-drain logic)
        // DDR=0 means input (line floats HIGH via pull-up)
        // DDR=1 means output (line driven LOW since ORA bits are 0)
        boolean oldScl = (oldDdrA & I2C_SCL) == 0;  // HIGH if DDR=0
        boolean newScl = (newDdrA & I2C_SCL) == 0;
        boolean oldSda = (oldDdrA & I2C_SDA) == 0;
        boolean newSda = (newDdrA & I2C_SDA) == 0;

        // During ACK phase (after 8 bits received), the master releases SDA to read ACK from slave.
        // This SDA rise should NOT be interpreted as STOP condition.
        // bitCount == 8 means we've received all data bits and are in ACK phase
        boolean inAckPhase = (i2cState != I2cState.IDLE && i2cBitCount == 8);


        // Detect START condition: SDA falls while SCL is high
        // START can happen anytime (including during a transaction = repeated START)
        if (newScl && oldSda && !newSda) {
            handleI2cStart();
        }
        // Detect STOP condition: SDA rises while SCL is high
        // But NOT during ACK phase when master releases SDA to read slave's ACK
        else if (newScl && !oldSda && newSda && !inAckPhase) {
            handleI2cStop();
        }
        // Detect SCL rising edge (data sampling)
        else if (!oldScl && newScl) {
            handleI2cSclRising(newSda);
        }
        // Detect SCL falling edge (data change allowed)
        else if (oldScl && !newScl) {
            handleI2cSclFalling();
        }

        // Update previous states
        i2cSclPrevious = newScl;
        i2cSdaPrevious = newSda;
    }

    /**
     * Handle I2C START condition
     */
    private void handleI2cStart() {
        logger.debug("I2C START detected");

        // If we were in a transaction, this is a repeated START
        if (i2cState != I2cState.IDLE && i2cActiveDevice != null) {
            logger.debug("I2C Repeated START");
            // Don't call stop on device - repeated start continues transaction
        }

        // Reset for new address byte
        i2cState = I2cState.ADDRESS;
        i2cBitCount = 0;
        i2cShiftReg = 0;
        i2cActiveDevice = null;
        i2cSlaveAck = false;
    }

    /**
     * Handle I2C STOP condition
     */
    private void handleI2cStop() {
        logger.debug("I2C STOP detected");

        // Notify active device
        if (i2cActiveDevice != null) {
            i2cActiveDevice.stop();
        }

        // Reset state
        i2cState = I2cState.IDLE;
        i2cBitCount = 0;
        i2cShiftReg = 0;
        i2cActiveDevice = null;
        i2cReadMode = false;
        i2cSlaveAck = false;
    }

    /**
     * Handle I2C SCL rising edge - sample SDA
     */
    private void handleI2cSclRising(boolean sda) {
        if (i2cState == I2cState.IDLE) {
            return;  // Nothing to do
        }

        if (i2cBitCount == 8) {
            // This is the 9th clock (ACK clock) rising edge
            // bitCount==8 means we finished 8 data bits and the 8th clock has fallen
            // Now on the 9th clock rising, master reads slave's ACK (or sends ACK in read mode)
            logger.debug("I2C ACK clock rising: state={}, sda={}, slaveAck={}", i2cState, sda ? 1 : 0, i2cSlaveAck);

            if (i2cState == I2cState.DATA_READ) {
                // Master sends ACK/NACK - SDA high = NACK (stop reading)
                boolean masterAck = !sda;  // ACK = SDA low
                logger.debug("I2C master {} read byte", masterAck ? "ACKed" : "NACKed");

                if (masterAck && i2cActiveDevice != null) {
                    // Prepare next byte
                    i2cReadByte = i2cActiveDevice.readByte(true);
                    logger.debug("I2C prepared next read byte: 0x{}", String.format("%02X", i2cReadByte));
                }
            }
            // For ADDRESS and DATA_WRITE: slave ACK is already set, master reads it via getI2cSdaValue()

            // Increment to 9 to mark that ACK clock has risen
            // On the next SCL falling edge, we'll see bitCount==9 and reset for next byte
            i2cBitCount = 9;
        } else if (i2cBitCount < 8) {
            // Receiving a data bit (from master during write, ignored during read)
            if (i2cState != I2cState.DATA_READ) {
                // Shift in the bit (MSB first)
                i2cShiftReg = (i2cShiftReg << 1) | (sda ? 1 : 0);
                logger.debug("I2C bit {}: SDA={}, shiftReg=0x{}",
                            i2cBitCount, sda ? 1 : 0, String.format("%02X", i2cShiftReg));
            }
            i2cBitCount++;

            // Check if byte is complete
            if (i2cBitCount == 8) {
                handleI2cByteComplete();
                // Now bitCount==8 which indicates we're waiting for ACK clock to rise
                // On next SCL rising (9th clock), bitCount will become 9
                // Slave will drive SDA with ACK value when master reads
            }
        }
        // bitCount==9 means we're waiting for ACK clock to fall - nothing to do on rising
    }

    /**
     * Handle I2C SCL falling edge - prepare for next bit
     */
    private void handleI2cSclFalling() {
        // bitCount==9 means ACK clock has risen and now fallen - reset for next byte
        // bitCount==8 means 8th data bit clock is falling - wait for ACK clock
        if (i2cBitCount == 9) {
            logger.debug("I2C ACK cycle complete on SCL falling edge, resetting for next byte");
            i2cBitCount = 0;
            i2cShiftReg = 0;
        }
        // In read mode, this is when slave should update SDA for next bit
        // The actual bit value is computed in getI2cSdaValue()
    }

    /**
     * Handle completion of an I2C byte (8 bits received)
     */
    private void handleI2cByteComplete() {
        int byteValue = i2cShiftReg & 0xFF;

        if (i2cState == I2cState.ADDRESS) {
            // Address byte: bits 7-1 = address, bit 0 = R/W
            int address = (byteValue >> 1) & 0x7F;
            i2cReadMode = (byteValue & 1) != 0;

            logger.debug("I2C address byte: 0x{} (addr=0x{}, {})",
                        String.format("%02X", byteValue),
                        String.format("%02X", address),
                        i2cReadMode ? "READ" : "WRITE");

            // Look up device
            i2cActiveDevice = i2cDevices.get(address);

            if (i2cActiveDevice != null) {
                // Device found - start transaction
                i2cSlaveAck = i2cActiveDevice.start(i2cReadMode);
                logger.debug("I2C device '{}' responded with {}",
                            i2cActiveDevice.getName(), i2cSlaveAck ? "ACK" : "NACK");

                if (i2cSlaveAck) {
                    if (i2cReadMode) {
                        // Switch to read mode - prepare first byte
                        i2cState = I2cState.DATA_READ;
                        i2cReadByte = i2cActiveDevice.readByte(true);
                        logger.debug("I2C read mode, first byte: 0x{}", String.format("%02X", i2cReadByte));
                    } else {
                        // Switch to write mode
                        i2cState = I2cState.DATA_WRITE;
                        // Reset register pointer for DS3231
                        if (i2cActiveDevice instanceof DS3231) {
                            ((DS3231) i2cActiveDevice).resetRegisterPointer();
                        }
                    }
                }
            } else {
                // No device at this address - NACK
                i2cSlaveAck = false;
                logger.debug("I2C no device at address 0x{}", String.format("%02X", address));
            }
        } else if (i2cState == I2cState.DATA_WRITE) {
            // Data byte to slave
            logger.debug("I2C write byte: 0x{}", String.format("%02X", byteValue));

            if (i2cActiveDevice != null) {
                i2cSlaveAck = i2cActiveDevice.writeByte(byteValue);
                logger.debug("I2C device {} write", i2cSlaveAck ? "ACKed" : "NACKed");
            } else {
                i2cSlaveAck = false;
            }
        }
        // DATA_READ bytes are handled in readByte calls during SCL rising edge
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

        // Reset I2C state
        i2cState = I2cState.IDLE;
        i2cSclPrevious = true;
        i2cSdaPrevious = true;
        i2cBitCount = 0;
        i2cShiftReg = 0;
        i2cActiveDevice = null;
        i2cReadMode = false;
        i2cReadByte = 0xFF;
        i2cSlaveAck = false;

        // Reset all I2C devices
        for (I2cDevice device : i2cDevices.values()) {
            device.reset();
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