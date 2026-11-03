package com.zipfoto.app.zip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Rilegge uno ZIP appena scritto e lo confronta con ciò che il writer dichiara di aver scritto.
 * È un lettore indipendente dal writer (parte dalla fine del file, come fa qualunque programma di estrazione):
 * se la directory centrale, le intestazioni o i dati non tornano, lo dice.
 *
 * <p>Controlli, in ordine di costo:
 * <ol>
 *   <li>fine archivio (anche Zip64) e directory centrale: numero di voci, nomi, CRC, dimensioni, posizioni;</li>
 *   <li>per ogni voce: intestazione locale, nome, data descriptor coerente, voci contigue senza buchi;</li>
 *   <li>in profondità, entro un budget di byte: decompressione e CRC dei dati realmente presenti nel file.</li>
 * </ol>
 */
public final class ZipVerifier {

    /** Sorgente ad accesso casuale (su Android: il FileChannel del file appena scritto). */
    public interface Source {
        long length() throws IOException;

        /** @return byte letti, o -1 a fine file. */
        int readAt(long position, byte[] buf, int off, int len) throws IOException;
    }

    public static final class Result {
        /** true se non è stato trovato alcun problema. */
        public final boolean ok;
        /** Tutti i problemi trovati (vuoto se ok). */
        public final List<String> problems;
        public final int entries;
        /** Voci di cui sono stati riletti e verificati i dati (CRC). */
        public final int deepEntries;
        public final long deepBytes;

        Result(List<String> problems, int entries, int deepEntries, long deepBytes) {
            this.ok = problems.isEmpty();
            this.problems = Collections.unmodifiableList(problems);
            this.entries = entries;
            this.deepEntries = deepEntries;
            this.deepBytes = deepBytes;
        }

        public boolean allDeep() {
            return ok && deepEntries == entries;
        }

        public String firstProblem() {
            return problems.isEmpty() ? null : problems.get(0);
        }
    }

    private static final class Cd {
        String name;
        long crc;
        long csize;
        long usize;
        long offset;
        int method;
    }

    private static final int SIG_LOCAL = 0x04034b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_Z64_EOCD = 0x06064b50;
    private static final int SIG_Z64_LOCATOR = 0x07064b50;
    private static final int SIG_DATA_DESC = 0x08074b50;
    private static final long MAX32 = 0xFFFFFFFFL;
    private static final int MAX16 = 0xFFFF;
    private static final int MAX_CD_BYTES = 256 * 1024 * 1024;
    private static final int MAX_PROBLEMS = 20;

    private ZipVerifier() {
    }

    /**
     * @param deepBudgetBytes quanti byte (compressi) rileggere al massimo per la verifica CRC; 0 = nessuna.
     *                        Si verificano prima le voci più piccole.
     */
    public static Result verify(Source src, List<StreamingZipWriter.EntryInfo> expected, long deepBudgetBytes) {
        return verify(src, expected, deepBudgetBytes, Collections.<String>emptySet());
    }

    /**
     * @param priorityNames nomi delle voci da rileggere per prime (es. i video), poi le altre dalla più piccola
     */
    public static Result verify(Source src, List<StreamingZipWriter.EntryInfo> expected, long deepBudgetBytes,
                                java.util.Set<String> priorityNames) {
        ArrayList<String> problems = new ArrayList<>();
        int deepEntries = 0;
        long deepBytes = 0;
        List<Cd> cd = null;
        try {
            cd = readCentralDirectory(src, problems);
            if (cd != null) {
                compare(cd, expected, problems);
                if (problems.isEmpty()) {
                    checkLocalParts(src, cd, problems);
                }
                if (problems.isEmpty() && deepBudgetBytes > 0) {
                    long[] r = deepCheck(src, cd, deepBudgetBytes, problems, priorityNames);
                    deepEntries = (int) r[0];
                    deepBytes = r[1];
                }
            }
        } catch (IOException e) {
            add(problems, "Errore nel rileggere lo ZIP: " + describe(e));
        } catch (RuntimeException e) {
            add(problems, "Struttura dello ZIP non valida (" + e.getClass().getSimpleName() + ")");
        }
        return new Result(problems, cd == null ? 0 : cd.size(), deepEntries, deepBytes);
    }

    // ---- directory centrale ---------------------------------------------------------------------

    private static List<Cd> readCentralDirectory(Source src, List<String> problems) throws IOException {
        long len = src.length();
        if (len < 22) {
            add(problems, "Il file ZIP è troppo corto (" + len + " byte)");
            return null;
        }
        int tailLen = (int) Math.min(len, 22L + MAX16);
        byte[] tail = new byte[tailLen];
        readFully(src, len - tailLen, tail, 0, tailLen);
        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (i32(tail, i) == SIG_EOCD) {
                int commentLen = u16(tail, i + 20);
                if (i + 22 + commentLen == tailLen) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0) {
            add(problems, "Fine archivio non trovata: il file ZIP è incompleto");
            return null;
        }
        long eocdPos = len - tailLen + eocd;
        long total = u16(tail, eocd + 10);
        long cdSize = u32(tail, eocd + 12);
        long cdOffset = u32(tail, eocd + 16);

        if (total == MAX16 || cdSize == MAX32 || cdOffset == MAX32) {
            if (eocdPos < 20) {
                add(problems, "Localizzatore Zip64 mancante");
                return null;
            }
            byte[] loc = new byte[20];
            readFully(src, eocdPos - 20, loc, 0, 20);
            if (i32(loc, 0) != SIG_Z64_LOCATOR) {
                add(problems, "Localizzatore Zip64 mancante");
                return null;
            }
            long z64Pos = i64(loc, 8);
            if (z64Pos < 0 || z64Pos + 56 > len) {
                add(problems, "Posizione Zip64 fuori dal file");
                return null;
            }
            byte[] z = new byte[56];
            readFully(src, z64Pos, z, 0, 56);
            if (i32(z, 0) != SIG_Z64_EOCD) {
                add(problems, "Fine archivio Zip64 non valida");
                return null;
            }
            total = i64(z, 32);
            cdSize = i64(z, 40);
            cdOffset = i64(z, 48);
        }
        if (cdSize < 0 || cdSize > MAX_CD_BYTES || cdOffset < 0 || cdOffset + cdSize > len) {
            add(problems, "Directory centrale fuori dal file (offset " + cdOffset + ", dimensione " + cdSize + ")");
            return null;
        }
        byte[] b = new byte[(int) cdSize];
        readFully(src, cdOffset, b, 0, b.length);
        ArrayList<Cd> out = new ArrayList<>();
        int p = 0;
        while (p < b.length) {
            if (p + 46 > b.length || i32(b, p) != SIG_CENTRAL) {
                add(problems, "Voce " + (out.size() + 1) + " della directory centrale non valida");
                return null;
            }
            int flags = u16(b, p + 8);
            Cd e = new Cd();
            e.method = u16(b, p + 10);
            e.crc = u32(b, p + 16);
            e.csize = u32(b, p + 20);
            e.usize = u32(b, p + 24);
            int nameLen = u16(b, p + 28);
            int extraLen = u16(b, p + 30);
            int commentLen = u16(b, p + 32);
            e.offset = u32(b, p + 42);
            int nameStart = p + 46;
            int extraStart = nameStart + nameLen;
            int next = extraStart + extraLen + commentLen;
            if (next > b.length) {
                add(problems, "Voce " + (out.size() + 1) + " della directory centrale troncata");
                return null;
            }
            e.name = new String(b, nameStart, nameLen,
                    (flags & 0x0800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            // Zip64 extra: campi presenti solo per quelli a 0xFFFFFFFF, nell'ordine usize, csize, offset
            int x = extraStart;
            int xEnd = extraStart + extraLen;
            while (x + 4 <= xEnd) {
                int id = u16(b, x);
                int sz = u16(b, x + 2);
                if (id == 0x0001) {
                    int q = x + 4;
                    int qEnd = Math.min(x + 4 + sz, xEnd);
                    if (e.usize == MAX32 && q + 8 <= qEnd) {
                        e.usize = i64(b, q);
                        q += 8;
                    }
                    if (e.csize == MAX32 && q + 8 <= qEnd) {
                        e.csize = i64(b, q);
                        q += 8;
                    }
                    if (e.offset == MAX32 && q + 8 <= qEnd) {
                        e.offset = i64(b, q);
                    }
                }
                x += 4 + sz;
            }
            out.add(e);
            p = next;
        }
        if (out.size() != total) {
            add(problems, "La directory centrale elenca " + out.size() + " voci, la fine archivio ne dichiara " + total);
            return null;
        }
        return out;
    }

    private static void compare(List<Cd> cd, List<StreamingZipWriter.EntryInfo> exp, List<String> problems) {
        if (cd.size() != exp.size()) {
            add(problems, "Nello ZIP ci sono " + cd.size() + " voci, ne erano state scritte " + exp.size());
            return;
        }
        for (int i = 0; i < exp.size(); i++) {
            Cd a = cd.get(i);
            StreamingZipWriter.EntryInfo b = exp.get(i);
            if (!a.name.equals(b.name)) {
                add(problems, "Voce " + (i + 1) + ": nome '" + a.name + "' invece di '" + b.name + "'");
            } else if (a.crc != b.crc || a.usize != b.usize || a.csize != b.csize || a.offset != b.offset) {
                add(problems, "'" + b.name + "': dati della directory centrale diversi da quelli scritti");
            }
        }
    }

    // ---- intestazioni locali e data descriptor ----------------------------------------------------

    private static void checkLocalParts(Source src, List<Cd> cd, List<String> problems) throws IOException {
        long len = src.length();
        long expectedOffset = 0;
        byte[] h = new byte[30];
        byte[] d = new byte[24];
        long lastEnd = 0;
        for (Cd e : cd) {
            if (e.offset != expectedOffset) {
                add(problems, "'" + e.name + "': posizione " + e.offset + " invece di " + expectedOffset
                        + " (buco o sovrapposizione nell'archivio)");
                return;
            }
            if (e.offset + 30 > len) {
                add(problems, "'" + e.name + "': intestazione fuori dal file");
                return;
            }
            readFully(src, e.offset, h, 0, 30);
            if (i32(h, 0) != SIG_LOCAL) {
                add(problems, "'" + e.name + "': intestazione locale non trovata");
                return;
            }
            int nameLen = u16(h, 26);
            int extraLen = u16(h, 28);
            if (nameLen != e.name.getBytes(StandardCharsets.UTF_8).length) {
                add(problems, "'" + e.name + "': nome nell'intestazione locale diverso");
                return;
            }
            long dataStart = e.offset + 30 + nameLen + extraLen;
            long dataEnd = dataStart + e.csize;
            boolean z64 = u32(h, 18) == MAX32 || e.csize >= MAX32 || e.usize >= MAX32;
            int dLen = z64 ? 24 : 16;
            if (dataEnd + dLen > len) {
                add(problems, "'" + e.name + "': i dati escono dal file (archivio troncato?)");
                return;
            }
            readFully(src, dataEnd, d, 0, dLen);
            if (i32(d, 0) != SIG_DATA_DESC) {
                add(problems, "'" + e.name + "': data descriptor non trovato dopo i dati");
                return;
            }
            long dCrc = u32(d, 4);
            long dC = z64 ? i64(d, 8) : u32(d, 8);
            long dU = z64 ? i64(d, 16) : u32(d, 12);
            if (dCrc != e.crc || dC != e.csize || dU != e.usize) {
                add(problems, "'" + e.name + "': data descriptor diverso dalla directory centrale");
                return;
            }
            expectedOffset = dataEnd + dLen;
            lastEnd = expectedOffset;
        }
        // subito dopo l'ultima voce deve iniziare la directory centrale
        if (!cd.isEmpty()) {
            byte[] sig = new byte[4];
            if (lastEnd + 4 > len) {
                add(problems, "Directory centrale non trovata dopo l'ultima voce");
                return;
            }
            readFully(src, lastEnd, sig, 0, 4);
            if (i32(sig, 0) != SIG_CENTRAL) {
                add(problems, "Dopo l'ultima voce non inizia la directory centrale");
            }
        }
    }

    // ---- verifica in profondità -------------------------------------------------------------------

    private static long[] deepCheck(Source src, List<Cd> cd, long budget, List<String> problems,
                                    final java.util.Set<String> priority) throws IOException {
        ArrayList<Cd> order = new ArrayList<>(cd);
        Collections.sort(order, new Comparator<Cd>() {
            @Override
            public int compare(Cd a, Cd b) {
                boolean pa = priority.contains(a.name);
                boolean pb = priority.contains(b.name);
                if (pa != pb) {
                    return pa ? -1 : 1;
                }
                return Long.compare(a.csize, b.csize);
            }
        });
        byte[] in = new byte[256 * 1024];
        byte[] out = new byte[256 * 1024];
        byte[] h = new byte[30];
        long used = 0;
        int done = 0;
        for (Cd e : order) {
            if (used + e.csize > budget) {
                continue;
            }
            readFully(src, e.offset, h, 0, 30);
            long pos = e.offset + 30 + u16(h, 26) + u16(h, 28);
            long remaining = e.csize;
            CRC32 crc = new CRC32();
            long produced = 0;
            Inflater inf = new Inflater(true);
            try {
                if (e.method != 8 && e.method != 0) {
                    add(problems, "'" + e.name + "': metodo di compressione " + e.method + " non previsto");
                    continue;
                }
                while (!inf.finished()) {
                    if (inf.needsInput()) {
                        if (remaining <= 0) {
                            break;
                        }
                        int n = (int) Math.min(in.length, remaining);
                        readFully(src, pos, in, 0, n);
                        pos += n;
                        remaining -= n;
                        inf.setInput(in, 0, n);
                    }
                    int c = inf.inflate(out);
                    if (c > 0) {
                        crc.update(out, 0, c);
                        produced += c;
                    } else if (inf.needsDictionary()) {
                        add(problems, "'" + e.name + "': flusso compresso non valido");
                        break;
                    }
                }
            } catch (DataFormatException ex) {
                add(problems, "'" + e.name + "': dati compressi corrotti (" + ex.getMessage() + ")");
                inf.end();
                continue;
            }
            boolean finished = inf.finished();
            inf.end();
            if (!finished) {
                add(problems, "'" + e.name + "': i dati finiscono prima del previsto");
            } else if (produced != e.usize) {
                add(problems, "'" + e.name + "': " + produced + " byte invece di " + e.usize);
            } else if (crc.getValue() != e.crc) {
                add(problems, "'" + e.name + "': il contenuto non corrisponde (CRC diverso)");
            } else {
                done++;
            }
            used += e.csize;
        }
        return new long[]{done, used};
    }

    // ---- utilità ----------------------------------------------------------------------------------

    private static void readFully(Source s, long pos, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = s.readAt(pos + got, buf, off + got, len - got);
            if (n <= 0) {
                throw new IOException("Fine file inattesa a " + (pos + got));
            }
            got += n;
        }
    }

    private static void add(List<String> problems, String msg) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(msg);
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    private static long u32(byte[] b, int i) {
        return i32(b, i) & MAX32;
    }

    private static long i64(byte[] b, int i) {
        return u32(b, i) | (u32(b, i + 4) << 32);
    }
}
