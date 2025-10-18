public class debug_address {
    public static void main(String[] args) {
        int address = 0x4030;
        int startAddress = 0x4030;
        int register = address - startAddress;
        System.out.println("address: 0x" + Integer.toHexString(address));
        System.out.println("startAddress: 0x" + Integer.toHexString(startAddress));
        System.out.println("register: 0x" + Integer.toHexString(register));
        System.out.println("register (signed): " + register);

        // Test other addresses
        int[] testAddresses = {0x4030, 0x4031, 0x4032, 0x4033};
        for (int addr : testAddresses) {
            int reg = addr - startAddress;
            System.out.println("Address 0x" + Integer.toHexString(addr) + " -> register 0x" + Integer.toHexString(reg) + " (" + reg + ")");
        }
    }
}