package ru.mail.polis.lsm;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

public class SSTable implements Closeable, Comparable<SSTable> {

    private static final Method CLEAN;

    static {
        try {
            Class<?> fileChannelImplClass = Class.forName("sun.nio.ch.FileChannelImpl");
            CLEAN = fileChannelImplClass.getDeclaredMethod("unmap", MappedByteBuffer.class);
            CLEAN.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private final SortedMap<ByteBuffer, Record> storage = new ConcurrentSkipListMap<>();

    private final MappedByteBuffer mmap;

    private final Path compareName;

    private static final String SAVE_FILE_NAME = "file_";
    private static final String TEMP_EXTENSION = "_temp";

    /**
     * Implementation of DAO that save data to the memory.
     */
    public SSTable(Path file) throws IOException {
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            mmap = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
        }
        compareName = file;
    }

    public static List<SSTable> loadFromDir(Path dir) throws IOException {
        List<SSTable> ssTableList = new ArrayList<>();
        for (File file : Objects.requireNonNull(dir.toFile().listFiles())) {
            ssTableList.add(new SSTable(file.toPath()));
        }

        Collections.sort(ssTableList);

        return ssTableList;
    }

    public static SSTable write(Iterator<Record> iterator, Path fileName) throws IOException {
        Path tempFileName = Path.of(fileName.toString() + TEMP_EXTENSION);
        try (FileChannel fileChannel = FileChannel.open(
                tempFileName,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer size = ByteBuffer.allocate(Integer.BYTES);
            while (iterator.hasNext()) {
                Record record = iterator.next();
                writeValueAndKey(record, fileChannel, size);
            }
            fileChannel.force(false);
        }

        Files.deleteIfExists(fileName);
        Files.move(tempFileName, fileName, StandardCopyOption.ATOMIC_MOVE);
        return new SSTable(fileName);
    }

    private static void writeValueAndKey(Record record, FileChannel fileChannel, ByteBuffer size) throws IOException {
        writeValue(record.getKey(), fileChannel, size);
        writeValue(record.getValue(), fileChannel, size);
    }

    private static void writeValue(ByteBuffer value, WritableByteChannel fileChannel, ByteBuffer temp) throws IOException {
        temp.position(0);
        temp.putInt(value == null ? -1 : value.remaining());
        temp.position(0);
        fileChannel.write(temp);
        if (value != null) {
            fileChannel.write(value);
        }
    }

    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        synchronized (this) {
            if (fromKey != null && toKey != null && fromKey.compareTo(toKey) > 0) {
                return Collections.emptyIterator();
            }
            return new RangeIterator(mmap.duplicate().slice().asReadOnlyBuffer(), fromKey, toKey);
        }
    }

    public void close() throws IOException {
        if (mmap != null) {
            try {
                CLEAN.invoke(null, mmap);
            } catch (IllegalAccessError | InvocationTargetException | IllegalAccessException e) {
                throw new IOException(e);
            }
        }
    }

    private SortedMap<ByteBuffer, Record> map(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        if (fromKey == null && toKey == null) {
            return storage;
        }
        if (fromKey == null) {
            return storage.headMap(toKey);
        }
        if (toKey == null) {
            return storage.tailMap(fromKey);
        }
        return storage.subMap(fromKey, toKey);
    }

    @Override
    public int compareTo(SSTable table) {
        int indexFirst =
                Integer.parseInt(this.compareName.getFileName().toString().substring(SAVE_FILE_NAME.length()));
        int indexSecond =
                Integer.parseInt(table.compareName.getFileName().toString().substring(SAVE_FILE_NAME.length()));
        return indexFirst - indexSecond;
    }

    private

    static class RangeIterator implements Iterator<Record> {

        private final ByteBuffer buffer;
        private final ByteBuffer fromKey;
        private final ByteBuffer toKey;

        private Record peek;

        RangeIterator(ByteBuffer buffer, ByteBuffer fromKey, ByteBuffer toKey) {
            this.buffer = buffer;
            this.fromKey = fromKey;
            this.toKey = toKey;

            updatePeek();
        }

        private void updatePeek() {
            if (buffer.hasRemaining()) {
                ByteBuffer key = readValue();
                ByteBuffer value = readValue();
                if (key == null) {
                    throw new NullPointerException();
                }
                if (value == null) {
                    peek = Record.tombstone(key);
                } else {
                    peek = Record.of(key, value);
                }
                while (fromKey != null && fromKey.compareTo(key) > 0) {
                    if (buffer.hasRemaining()) {
                        key = readValue();
                        value = readValue();
                        if (key == null) {
                            throw new NullPointerException();
                        }
                        if (value == null) {
                            peek = Record.tombstone(key);
                        } else {
                            peek = Record.of(key, value);
                        }
                    } else {
                        peek = null;
                    }
                }

            } else {
                peek = null;
            }
        }

        @Override
        public boolean hasNext() {
            if (toKey != null && toKey.compareTo(peek.getKey()) < 0) {
                return false;
            }
            return peek != null;
        }

        @Override
        public Record next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Record buffer = peek;
            updatePeek();
            return buffer;
        }

        private ByteBuffer readValue() {
            int valueSize = buffer.getInt();
            if(valueSize < 0) {
                return null;
            }
            ByteBuffer value = buffer.slice().limit(valueSize).slice();
            buffer.position(buffer.position() + valueSize);
            return value;
        }
    }

}