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

package com.loomcom.symon.machines;

import com.loomcom.symon.Bus;
import com.loomcom.symon.Cpu;
import com.loomcom.symon.devices.*;
import com.loomcom.symon.exceptions.MemoryRangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Waffle2e Computer System Emulation
 * 
 * A 65C02-based computer system with:
 * - 16KB RAM ($0000-$3FFF)
 * - I/O space ($4000-$4FFF) 
 * - 32KB ROM ($8000-$FFFF)
 * 
 * Peripherals:
 * - VibesGraphicsArray video controller ($4000-$400F)
 * - Dual W65C51 ACIA serial ports ($4010-$4013, $4110-$4113)
 * - W65C22 VIA PS/2 interface ($4020-$4023)
 */
public class Waffle2eMachine implements Machine {
    
    private final static Logger logger = LoggerFactory.getLogger(Waffle2eMachine.class.getName());
    
    // Memory map constants
    private static final int BUS_BOTTOM = 0x0000;
    private static final int BUS_TOP    = 0xFFFF;
    
    // 16KB RAM from $0000-$3FFF
    private static final int RAM_BASE = 0x0000;
    private static final int RAM_SIZE = 0x4000;
    
    // I/O Space $4000-$4FFF
    private static final int IO_BASE = 0x4000;

    // Video Controller (VibesGraphicsArray) at $4000-$400F
    private static final int VIDEO_BASE = 0x4000;

    // Serial Port 0 (W65C51 ACIA) at $4010-$4013
    private static final int SERIAL0_BASE = 0x4010;

    // PS/2 Interface (W65C22 VIA) at $4020-$402F
    private static final int PS2_BASE = 0x4020;

    // Peripheral Controller (W65C22 VIA) at $4070-$407F (SD card/SPI)
    private static final int PERIPHERAL_BASE = 0x4070;

    // Serial Port 1 (W65C51 ACIA) at $4110-$4113
    private static final int SERIAL1_BASE = 0x4110;
    
    // 32KB ROM from $8000-$FFFF
    private static final int ROM_BASE = 0x8000;
    private static final int ROM_SIZE = 0x8000;
    
    // Simulated components
    private final Bus    bus;
    private final Cpu    cpu;
    private final Memory ram;
    private       Memory rom;
    
    // Peripherals
    private final VibesGraphicsArray video;
    private final Acia   serial0;
    private final Acia   serial1;
    private final PS2Interface ps2Interface;
    private final PeripheralController peripheralController;
    private final SpiSDCard sdCard;
    private final DS3231 rtc;
    
    public Waffle2eMachine(String romFile) throws Exception {
        this.bus = new Bus(BUS_BOTTOM, BUS_TOP);
        this.cpu = new Cpu();
        this.ram = new Memory(RAM_BASE, RAM_BASE + RAM_SIZE - 1, false);
        
        // Initialize peripherals
        this.video = new VibesGraphicsArray(VIDEO_BASE);
        this.serial0 = new Acia6551(SERIAL0_BASE);
        this.serial1 = new Acia6551(SERIAL1_BASE);
        this.ps2Interface = new PS2Interface(PS2_BASE);
        this.peripheralController = new PeripheralController(PERIPHERAL_BASE);

        // Initialize SD card and register it on SPI CS0
        this.sdCard = new SpiSDCard();

        // Mount test disk image if available
        String testDiskPath = "/Users/johnwolthuis/projects/waffle2e_computer/software/test_programs/test_disk_old.img";
        File testDisk = new File(testDiskPath);
        if (testDisk.exists()) {
            logger.info("Mounting test disk image: {}", testDiskPath);
            sdCard.mountImage(testDiskPath);
        } else {
            logger.warn("Test disk image not found at: {}", testDiskPath);
        }

        this.peripheralController.registerSpiDevice(0, sdCard);

        // Initialize and register DS3231 RTC on I2C bus
        this.rtc = new DS3231();
        this.peripheralController.registerI2cDevice(rtc);

        // Add components to bus
        bus.addCpu(cpu);
        bus.addDevice(ram);
        bus.addDevice(video);
        bus.addDevice(serial0);
        bus.addDevice(serial1);
        bus.addDevice(ps2Interface);
        bus.addDevice(peripheralController);
        
        // Handle ROM loading
        if (romFile != null) {
            File romImage = new File(romFile);
            if (romImage.canRead()) {
                logger.info("Loading Waffle2e ROM image from file {}", romImage);
                this.rom = Memory.makeROM(ROM_BASE, ROM_BASE + ROM_SIZE - 1, romImage);
            } else {
                logger.info("ROM file {} not found, loading empty R/W memory image.", romImage);
                this.rom = Memory.makeRAM(ROM_BASE, ROM_BASE + ROM_SIZE - 1);
            }
        } else {
            logger.info("No ROM file specified, loading empty R/W memory image.");
            this.rom = Memory.makeRAM(ROM_BASE, ROM_BASE + ROM_SIZE - 1);
        }
        
        bus.addDevice(rom);
        
        logger.info("Waffle2e Computer System initialized");
        logger.info("RAM: {}-{} ({}KB)", String.format("%04X", RAM_BASE), String.format("%04X", RAM_BASE + RAM_SIZE - 1), RAM_SIZE / 1024);
        logger.info("ROM: {}-{} ({}KB)", String.format("%04X", ROM_BASE), String.format("%04X", ROM_BASE + ROM_SIZE - 1), ROM_SIZE / 1024);
        logger.info("Video Controller: {}-{}", String.format("%04X", VIDEO_BASE), String.format("%04X", VIDEO_BASE + 0x0F));
        logger.info("Serial Port 0: {}-{}", String.format("%04X", SERIAL0_BASE), String.format("%04X", SERIAL0_BASE + 0x03));
        logger.info("Serial Port 1: {}-{}", String.format("%04X", SERIAL1_BASE), String.format("%04X", SERIAL1_BASE + 0x03));
        logger.info("PS/2 Interface: {}-{}", String.format("%04X", PS2_BASE), String.format("%04X", PS2_BASE + 0x03));
        logger.info("Peripheral Controller: {}-{}", String.format("%04X", PERIPHERAL_BASE), String.format("%04X", PERIPHERAL_BASE + 0x0F));
        logger.info("DS3231 RTC: I2C address 0x{}", String.format("%02X", DS3231.I2C_ADDRESS));
    }
    
    @Override
    public Bus getBus() {
        return bus;
    }
    
    @Override
    public Cpu getCpu() {
        return cpu;
    }
    
    @Override
    public Memory getRam() {
        return ram;
    }
    
    @Override
    public Acia getAcia() {
        return serial0;  // Return primary serial port
    }
    
    @Override
    public Pia getPia() {
        return null;  // Waffle2e uses custom PS/2 interface, not generic PIA
    }
    
    @Override
    public Crtc getCrtc() {
        return null;  // Waffle2e uses custom video controller, not CRTC
    }
    
    @Override
    public Memory getRom() {
        return rom;
    }
    
    @Override
    public void setRom(Memory rom) throws MemoryRangeException {
        if (this.rom != null) {
            bus.removeDevice(this.rom);
        }
        this.rom = rom;
        bus.addDevice(this.rom);
    }
    
    @Override
    public int getRomBase() {
        return ROM_BASE;
    }
    
    @Override
    public int getRomSize() {
        return ROM_SIZE;
    }
    
    @Override
    public int getMemorySize() {
        return RAM_SIZE;
    }
    
    @Override
    public String getName() {
        return "Waffle2e";
    }
    
    // Waffle2e specific methods
    public VibesGraphicsArray getVideoController() {
        return video;
    }
    
    public Acia getSerial0() {
        return serial0;
    }
    
    public Acia getSerial1() {
        return serial1;
    }
    
    public PS2Interface getPS2Interface() {
        return ps2Interface;
    }

    /**
     * Mount a disk image file in the SD card
     * @param imagePath path to disk image file
     * @return true if successful
     */
    public boolean mountDiskImage(String imagePath) {
        return sdCard.mountImage(imagePath);
    }

    /**
     * Unmount current disk image
     */
    public void unmountDiskImage() {
        sdCard.unmountImage();
    }

    /**
     * Get current disk image path
     * @return image path or null if no image mounted
     */
    public String getDiskImagePath() {
        return sdCard.getImagePath();
    }

    /**
     * Check if a disk image is mounted
     * @return true if image is mounted
     */
    public boolean isDiskImageMounted() {
        return sdCard.isImageMounted();
    }

    /**
     * Get SD card device for direct access
     * @return SD card device
     */
    public SpiSDCard getSDCard() {
        return sdCard;
    }

    /**
     * Get peripheral controller for SPI/I2C access
     * @return peripheral controller
     */
    public PeripheralController getPeripheralController() {
        return peripheralController;
    }

    /**
     * Get DS3231 RTC device
     * @return RTC device
     */
    public DS3231 getRTC() {
        return rtc;
    }
}