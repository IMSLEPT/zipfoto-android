package com.zipfoto.app.zip;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writer ZIP in streaming, pensato per archivi molto grandi (centinaia di foto, anche oltre 4 GB).
 *
 * <ul>
 *   <li>Scrive in sequenza su un normale OutputStream: nessun seek, memoria costante.</li>
 *   <li>Ogni file viene copiato byte per byte: con level = 0 viene usato DEFLATE con blocchi "stored",
 *       quindi nessuna ricompressione e nessuna perdita (le foto JPEG/HEIC sono già compresse).</li>
 *   <li>Zip64 automatico per l'archivio (oltre 4 GB di offset, oltre 65535 file).
 *       Il singolo file deve restare sotto i 4 GB.</li>
 *   <li>Nomi in UTF-8 (flag bit 11), data descriptor dopo ogni file.</li>
 * </ul>
 */
public final class StreamingZipWriter implements Closeable {

    /** Notificato a ogni blocco letto dal file sorgente. */
    public interface Listener {
        void onBytesRead(int n);
    }

    private static final int SIG_LOCAL = 0x04034b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_Z64_EOCD = 0x06064b50;
    private static final int SIG_Z64_LOCATOR = 0x07064b50;
    private static final int SIG_DATA_DESC = 0x08074b50;

    private static final long MAX32 = 0xFFFFFFFFL;
    private static final int MAX16 = 0xFFFF;
    /** bit 3 = data descriptor, bit 11 = nomi UTF-8 */
    private static final int FLAGS = 0x0008 | 0x0800;
    private static final int BUF_SIZE = 256 * 1024;

    private static final class CdEntry {
        byte[] name;
        int dosTime;
        int dosDate;
        long crc;
        long csize;
        long usize;
        long offset;
    }

    private static final class CountingOut extends OutputStream {
        private final OutputStream target;
        long count;

        CountingOut(OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }

    private final CountingOut out;
    private final int level;
    private final ArrayList<CdEntry> entries = new ArrayList<>();
    private final byte[] inBuf = new byte[BUF_SIZE];
    private final byte[] outBuf = new byte[BUF_SIZE];
    private boolean finished;

    /**
     * @param out   destinazione (viene chiusa da {@link #close()})
     * @param level 0 = nessuna compressione (consigliato per foto), 1..9 = DEFLATE
     */
    public StreamingZipWriter(OutputStream out, int level) {
        if (level < 0 || level > 9) {
            throw new IllegalArgumentException("level deve essere tra 0 e 9");
        }
        this.out = new CountingOut(out);
        this.level = level;
    }

    /** Byte scritti finora nell'archivio. */
    public long bytesWritten() {
        return out.count;
    }

    public int entryCount() {
        return entries.size();
    }

    /**
     * Aggiunge un file. In caso di errore in lettura a metà file l'archivio non è più valido:
     * il chiamante deve scartarlo. Errori in apertura vanno gestiti PRIMA di chiamare questo metodo.
     */
    public void addEntry(String name, long lastModifiedMillis, InputStream in, Listener listener)
            throws IOException {
        if (finished) {
            throw new IllegalStateException("Archivio già chiuso");
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > MAX16) {
            throw new IOException("Nome file troppo lungo: " + name);
        }
        int[] dos = toDos(lastModifiedMillis);
        long offset = out.count;

        ByteBuffer h = le(30 + nameBytes.length);
        h.putInt(SIG_LOCAL);
        h.putShort((short) 20);          // version needed
        h.putShort((short) FLAGS);
        h.putShort((short) 8);           // method: deflate
        h.putShort((short) dos[0]);
        h.putShort((short) dos[1]);
        h.putInt(0);                     // crc  (nel data descriptor)
        h.putInt(0);                     // csize
        h.putInt(0);                     // usize
        h.putShort((short) nameBytes.length);
        h.putShort((short) 0);           // extra length
        h.put(nameBytes);
        out.write(h.array(), 0, h.position());

        CRC32 crc = new CRC32();
        Deflater deflater = new Deflater(level, true);
        long usize = 0;
        long csize = 0;
        try {
            int n;
            while ((n = in.read(inBuf)) != -1) {
                if (n == 0) {
                    continue;
                }
                crc.update(inBuf, 0, n);
                usize += n;
                if (usize >= MAX32) {
                    throw new IOException("File troppo grande per questo archivio: " + name);
                }
                deflater.setInput(inBuf, 0, n);
                while (!deflater.needsInput()) {
                    int c = deflater.deflate(outBuf);
                    if (c > 0) {
                        out.write(outBuf, 0, c);
                        csize += c;
                    }
                }
                if (listener != null) {
                    listener.onBytesRead(n);
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int c = deflater.deflate(outBuf);
                if (c > 0) {
                    out.write(outBuf, 0, c);
                    csize += c;
                }
            }
        } finally {
            deflater.end();
        }
        if (csize >= MAX32) {
            throw new IOException("File troppo grande per questo archivio: " + name);
        }

        ByteBuffer d = le(16);
        d.putInt(SIG_DATA_DESC);
        d.putInt((int) crc.getValue());
        d.putInt((int) csize);
        d.putInt((int) usize);
        out.write(d.array(), 0, d.position());

        CdEntry e = new CdEntry();
        e.name = nameBytes;
        e.dosTime = dos[0];
        e.dosDate = dos[1];
        e.crc = crc.getValue();
        e.csize = csize;
        e.usize = usize;
        e.offset = offset;
        entries.add(e);
    }

    /** Scrive directory centrale e fine archivio (Zip64 se serve). Va chiamato una sola volta. */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        long cdStart = out.count;
        for (CdEntry e : entries) {
            boolean z64 = e.offset >= MAX32;
            int extraLen = z64 ? 12 : 0;
            int version = z64 ? 45 : 20;
            ByteBuffer c = le(46 + e.name.length + extraLen);
            c.putInt(SIG_CENTRAL);
            c.putShort((short) version);     // version made by
            c.putShort((short) version);     // version needed
            c.putShort((short) FLAGS);
            c.putShort((short) 8);
            c.putShort((short) e.dosTime);
            c.putShort((short) e.dosDate);
            c.putInt((int) e.crc);
            c.putInt((int) e.csize);
            c.putInt((int) e.usize);
            c.putShort((short) e.name.length);
            c.putShort((short) extraLen);
            c.putShort((short) 0);           // comment length
            c.putShort((short) 0);           // disk number start
            c.putShort((short) 0);           // internal attrs
            c.putInt(0);                     // external attrs
            c.putInt(z64 ? (int) MAX32 : (int) e.offset);
            c.put(e.name);
            if (z64) {
                c.putShort((short) 0x0001);  // Zip64 extended information
                c.putShort((short) 8);
                c.putLong(e.offset);
            }
            out.write(c.array(), 0, c.position());
        }
        long cdSize = out.count - cdStart;
        int n = entries.size();
        boolean z64 = n >= MAX16 || cdStart >= MAX32 || cdSize >= MAX32;

        if (z64) {
            long z64Pos = out.count;
            ByteBuffer r = le(56);
            r.putInt(SIG_Z64_EOCD);
            r.putLong(44);                   // size of remaining record
            r.putShort((short) 45);
            r.putShort((short) 45);
            r.putInt(0);                     // this disk
            r.putInt(0);                     // disk with cd
            r.putLong(n);
            r.putLong(n);
            r.putLong(cdSize);
            r.putLong(cdStart);
            out.write(r.array(), 0, r.position());

            ByteBuffer loc = le(20);
            loc.putInt(SIG_Z64_LOCATOR);
            loc.putInt(0);
            loc.putLong(z64Pos);
            loc.putInt(1);
            out.write(loc.array(), 0, loc.position());
        }

        ByteBuffer e = le(22);
        e.putInt(SIG_EOCD);
        e.putShort((short) 0);
        e.putShort((short) 0);
        e.putShort((short) (z64 ? MAX16 : n));
        e.putShort((short) (z64 ? MAX16 : n));
        e.putInt(z64 ? (int) MAX32 : (int) cdSize);
        e.putInt(z64 ? (int) MAX32 : (int) cdStart);
        e.putShort((short) 0);
        out.write(e.array(), 0, e.position());
        out.flush();
        finished = true;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** @return {time, date} in formato MS-DOS */
    private static int[] toDos(long millis) {
        if (millis <= 0) {
            return new int[]{0, (1 << 5) | 1};  // 1980-01-01 00:00
        }
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        int year = t.getYear();
        if (year < 1980) {
            return new int[]{0, (1 << 5) | 1};
        }
        if (year > 2107) {
            year = 2107;
        }
        int time = (t.getHour() << 11) | (t.getMinute() << 5) | (t.getSecond() / 2);
        int date = ((year - 1980) << 9) | (t.getMonthValue() << 5) | t.getDayOfMonth();
        return new int[]{time, date};
    }
}
