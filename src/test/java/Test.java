import net.creativious.SharedMemory;
import net.creativious.SystemType;

public class Test {

    @org.junit.jupiter.api.Test
    public void testExample() {
        var shm = SharedMemory.create("test", 1024);
        byte[] data = shm.read_data();
        String message = new String(data);
        System.out.println(message);
    }
}
