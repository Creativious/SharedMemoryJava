import net.creativious.SharedMemory;

public class Test {

    @org.junit.jupiter.api.Test
    public void testExample() {
        var shm = SharedMemory.create("test", 1024);
        var buf = shm.toByteBuffer();
        buf.put("Does this actually work???".getBytes());

        while (true) {
            // do nothing
        }
    }
}
