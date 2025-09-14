import com.loomcom.symon.machines.Waffle2eMachine;
import java.io.File;

/**
 * Simple test to verify Waffle2eMachine implementation
 */
public class TestWaffle2eMachine {
    public static void main(String[] args) {
        try {
            System.out.println("Testing Waffle2eMachine initialization...");
            
            // Test with ROM file
            String romPath = "software/os/build/kernel.bin";
            if (new File(romPath).exists()) {
                System.out.println("Found ROM: " + romPath);
                Waffle2eMachine machine = new Waffle2eMachine(romPath);
                
                System.out.println("Machine: " + machine.getName());
                System.out.println("RAM Size: " + machine.getMemorySize() + " bytes");
                System.out.println("ROM Base: $" + Integer.toHexString(machine.getRomBase()).toUpperCase());
                System.out.println("ROM Size: " + machine.getRomSize() + " bytes");
                
                System.out.println("Video Controller: " + machine.getVideoController());
                System.out.println("Serial Port 0: " + machine.getSerial0());
                System.out.println("Serial Port 1: " + machine.getSerial1());
                System.out.println("PS/2 Interface: " + machine.getPS2Interface());
                
                System.out.println("\nWaffle2eMachine test PASSED!");
            } else {
                System.out.println("ROM not found at " + romPath);
                System.out.println("Testing without ROM...");
                
                Waffle2eMachine machine = new Waffle2eMachine(null);
                System.out.println("Machine: " + machine.getName());
                System.out.println("Waffle2eMachine test PASSED (no ROM)!");
            }
            
        } catch (Exception e) {
            System.err.println("Waffle2eMachine test FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}