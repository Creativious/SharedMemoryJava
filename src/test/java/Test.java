import net.creativious.SharedMemoryWrapper;

public class Test {

    @org.junit.jupiter.api.Test
    public void testExample() {
        var shm = SharedMemoryWrapper.open("test", 1024);
        String message = shm.read_string();
        System.out.println(message);
    }
}
