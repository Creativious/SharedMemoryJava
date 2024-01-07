package net.creativious;

import cn.apisium.shm.SharedMemory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SharedMemoryWrapper {

    private SharedMemory shm;

    public static SharedMemoryWrapper create(String name, int size) {
        return new SharedMemoryWrapper(true, name, size);
    }

    public static SharedMemoryWrapper open(String name, int size) {
        return new SharedMemoryWrapper(false, name, size);
    }

    public SharedMemoryWrapper(boolean is_create, String name, int size) {
        if (is_create) {
            shm = SharedMemory.create(name, size);
        } else {
            shm = SharedMemory.open(name, size);
        }
    }

    public void write_data(byte[] data) {
        ByteBuffer buffer = shm.toByteBuffer();
        buffer.put(data);
        if (data.length < shm.getSize()) {
            int remainingBytes = shm.getSize() - data.length;
            for (int i = 0; i < remainingBytes; i++) {
                buffer.put((byte) 0);
            }
        }
    }

    public byte[] read_data() {
        ByteBuffer buffer = shm.toByteBuffer();
        byte[] data = new byte[shm.getSize()];
        buffer.get(data);

        int lastNonZeroIndex = data.length - 1;
        while (lastNonZeroIndex >= 0 && data[lastNonZeroIndex] == 0) {
            lastNonZeroIndex--;
        }

        return Arrays.copyOfRange(data, 0, lastNonZeroIndex + 1);
    }

    public void write_string(String str) {
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        write_data(data);
    }

    public String read_string() {
        byte[] data = read_data();
        return new String(data, StandardCharsets.UTF_8);
    }
}
