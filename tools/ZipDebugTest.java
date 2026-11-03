import com.zipfoto.app.zip.MediaSniffer;
import com.zipfoto.app.zip.StreamingZipWriter;
import com.zipfoto.app.zip.ZipNames;
import com.zipfoto.app.zip.ZipVerifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Test della catena ZIP, eseguibili su una JVM qualsiasi (nessuna dipendenza da Android).
 *
 * Compilare ed eseguire dalla radice del repository:
 *   javac -d build/ziptest app/src/main/java/com/zipfoto/app/zip/*.java tools/ZipDebugTest.java
 *   LC_ALL=C.UTF-8 java -cp build/ziptest ZipDebugTest
 *
 * (La JVM va avviata con una locale UTF-8 per poter confrontare file con nomi accentati; i programmi esterni,
 * come "unzip", vengono invece lanciati con locale "C": unzip 6.0 con locale UTF-8 sbaglia a decodificare i nomi.)
 *
 * Ogni archivio prodotto viene riletto da lettori INDIPENDENTI dal nostro codice:
 * java.util.zip.ZipFile, "unzip -t" e il modulo zipfile di Python (se presenti), oltre al nostro ZipVerifier.
 */
public class ZipDebugTest {

    static int passed = 0;
    static int failed = 0;
    static boolean haveUnzip;
    static boolean havePython;
    static File tmp;

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("zipdebug").toFile();
        haveUnzip = run("unzip", "-v") == 0;
        havePython = run("python3", "-c", "import zipfile") == 0;
        System.out.println("Lettori esterni: unzip=" + haveUnzip + " python=" + havePython);

        testVideoLikeStored();
        testZeroByteEntry();
        testMixedPhotosAndVideosUtf8();
        testForcedZip64SmallEntry();
        testOddReadChunks();
        testDeflatePhoto();
        testManyEntriesArchiveZip64();
        testReturnValueAndInfos();
        testVerifierDetectsTruncation();
        testVerifierDetectsFlippedData();
        testVerifierDetectsBadDirectory();
        testVerifierPriorityForVideos();
        testNamesUnique();
        testSniffer();
        testZip64ThresholdPolicy();
        testTooBigWithoutZip64();

        deleteAll(tmp);
        System.out.println();
        System.out.println("Test riusciti: " + passed + ", falliti: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- scenari ---------------------------------------------------------------------------------

    static void testVideoLikeStored() throws Exception {
        String t = "video da 24 MB, copia senza ricompressione";
        byte[] video = videoLike(24 * 1024 * 1024, 1);
        File f = new File(tmp, "video.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f, new Item("VID_0001.mp4", video, 0, false));
        checkEverything(t, f, infos, new String[]{"VID_0001.mp4"}, new byte[][]{video});
        // "stored" = il flusso deve essere grande poco più dell'originale (nessuna ricompressione)
        StreamingZipWriter.EntryInfo e = infos.get(0);
        check(t + ": dimensione compressa vicina all'originale", e.csize >= e.usize && e.csize < e.usize + e.usize / 100 + 1024);
    }

    static void testZeroByteEntry() throws Exception {
        String t = "file vuoto (0 byte)";
        File f = new File(tmp, "empty.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f,
                new Item("vuoto.mp4", new byte[0], 0, false),
                new Item("pieno.jpg", videoLike(5000, 2), 0, false));
        checkEverything(t, f, infos, new String[]{"vuoto.mp4", "pieno.jpg"},
                new byte[][]{new byte[0], videoLike(5000, 2)});
    }

    static void testMixedPhotosAndVideosUtf8() throws Exception {
        String t = "foto + video, nomi UTF-8 e sottocartelle";
        byte[] a = videoLike(300_000, 3);
        byte[] b = videoLike(8 * 1024 * 1024, 4);
        byte[] c = videoLike(1_200_000, 5);
        File f = new File(tmp, "mixed.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f,
                new Item("Vacanze/IMG_20240801_è.jpg", a, 0, false),
                new Item("Vacanze/MOV_Città_☀.mp4", b, 0, false),
                new Item("Vacanze/Sotto/clip 1.mov", c, 0, false));
        checkEverything(t, f, infos,
                new String[]{"Vacanze/IMG_20240801_è.jpg", "Vacanze/MOV_Città_☀.mp4", "Vacanze/Sotto/clip 1.mov"},
                new byte[][]{a, b, c});
    }

    static void testForcedZip64SmallEntry() throws Exception {
        String t = "voce Zip64 forzata su un video piccolo";
        byte[] video = videoLike(3 * 1024 * 1024, 6);
        byte[] photo = videoLike(200_000, 7);
        File f = new File(tmp, "z64entry.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f,
                new Item("a.mp4", video, 0, true),
                new Item("b.jpg", photo, 0, false),
                new Item("c.mp4", video, 0, true));
        checkEverything(t, f, infos, new String[]{"a.mp4", "b.jpg", "c.mp4"}, new byte[][]{video, photo, video});
        check(t + ": voci marcate Zip64", infos.get(0).zip64 && !infos.get(1).zip64 && infos.get(2).zip64);
    }

    static void testOddReadChunks() throws Exception {
        String t = "sorgente che restituisce pezzi minuscoli, vuoti e irregolari";
        byte[] video = videoLike(700_000, 8);
        File f = new File(tmp, "odd.zip");
        List<StreamingZipWriter.EntryInfo> infos;
        try (StreamingZipWriter w = new StreamingZipWriter(new FileOutputStream(f), 0)) {
            long n1 = w.addEntry("1byte.mp4", 0, new OddStream(video, new int[]{1}), null, 0, true);
            long n2 = w.addEntry("irregolare.mp4", 0, new OddStream(video, new int[]{7, 0, 100_000, 3, 65_536, 1}), null, 0, true);
            check(t + ": byte letti 1", n1 == video.length);
            check(t + ": byte letti 2", n2 == video.length);
            w.finish();
            infos = w.entryInfos();
        }
        checkEverything(t, f, infos, new String[]{"1byte.mp4", "irregolare.mp4"}, new byte[][]{video, video});
    }

    static void testDeflatePhoto() throws Exception {
        String t = "foto con DEFLATE livello 6";
        byte[] text = new byte[2_000_000];
        for (int i = 0; i < text.length; i++) {
            text[i] = (byte) ('a' + (i % 7));
        }
        File f = new File(tmp, "deflate.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f, new Item("raw.dng", text, 6, false));
        checkEverything(t, f, infos, new String[]{"raw.dng"}, new byte[][]{text});
        check(t + ": ha davvero compresso", infos.get(0).csize < infos.get(0).usize / 4);
    }

    static void testManyEntriesArchiveZip64() throws Exception {
        String t = "70.000 voci (Zip64 a livello di archivio)";
        File f = new File(tmp, "many.zip");
        ArrayList<byte[]> small = new ArrayList<>();
        List<StreamingZipWriter.EntryInfo> infos;
        try (StreamingZipWriter w = new StreamingZipWriter(new FileOutputStream(f), 0)) {
            for (int i = 0; i < 70_000; i++) {
                w.addEntry("f/" + i + ".jpg", 0, new ByteArrayInputStream(new byte[]{(byte) i, 1, 2}), null);
            }
            w.finish();
            infos = w.entryInfos();
        }
        try (ZipFile z = new ZipFile(f)) {
            check(t + ": ZipFile vede 70.000 voci", z.size() == 70_000);
        }
        ZipVerifier.Result r = verify(f, infos, 1L << 30);
        check(t + ": ZipVerifier ok " + r.problems, r.ok && r.deepEntries == 70_000);
        if (haveUnzip) {
            check(t + ": unzip -t", run("unzip", "-tq", f.getPath()) == 0);
        }
        if (havePython) {
            check(t + ": python zipfile", run("python3", "-c",
                    "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert len(z.namelist())==70000; assert z.testzip() is None",
                    f.getPath()) == 0);
        }
    }

    static void testReturnValueAndInfos() throws Exception {
        String t = "addEntry restituisce i byte letti ed entryInfos coincide";
        byte[] d = videoLike(123_457, 9);
        File f = new File(tmp, "ret.zip");
        try (StreamingZipWriter w = new StreamingZipWriter(new FileOutputStream(f), 0)) {
            long n = w.addEntry("x.mp4", 0, new ByteArrayInputStream(d), null);
            check(t + ": valore restituito", n == d.length);
            w.finish();
            StreamingZipWriter.EntryInfo e = w.entryInfos().get(0);
            check(t + ": usize", e.usize == d.length && e.offset == 0 && e.name.equals("x.mp4"));
        }
    }

    static void testVerifierDetectsTruncation() throws Exception {
        String t = "il verificatore scopre un file troncato";
        byte[] video = videoLike(2_000_000, 10);
        File f = new File(tmp, "trunc.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f, new Item("v.mp4", video, 0, false));
        File g = new File(tmp, "trunc2.zip");
        byte[] all = Files.readAllBytes(f.toPath());
        Files.write(g.toPath(), Arrays.copyOf(all, all.length - 100));
        ZipVerifier.Result r = verify(g, infos, 1L << 30);
        check(t + ": segnala il problema (" + r.firstProblem() + ")", !r.ok);
        // anche se mancano solo i dati a metà (file "bucato" in fondo alla fine archivio)
        File h = new File(tmp, "trunc3.zip");
        Files.write(h.toPath(), Arrays.copyOf(all, 1000));
        r = verify(h, infos, 1L << 30);
        check(t + ": file di soli 1000 byte", !r.ok);
    }

    static void testVerifierDetectsFlippedData() throws Exception {
        String t = "il verificatore scopre dati alterati";
        byte[] video = videoLike(1_000_000, 11);
        File f = new File(tmp, "flip.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f, new Item("v.mp4", video, 0, false));
        byte[] all = Files.readAllBytes(f.toPath());
        all[500_000] ^= 0x55;
        File g = new File(tmp, "flip2.zip");
        Files.write(g.toPath(), all);
        ZipVerifier.Result shallow = verify(g, infos, 0);
        check(t + ": senza verifica profonda la struttura sembra a posto", shallow.ok);
        ZipVerifier.Result deep = verify(g, infos, 1L << 30);
        check(t + ": con verifica profonda lo vede (" + deep.firstProblem() + ")", !deep.ok);
    }

    static void testVerifierDetectsBadDirectory() throws Exception {
        String t = "il verificatore scopre una directory centrale diversa dal previsto";
        byte[] video = videoLike(100_000, 12);
        File f = new File(tmp, "dir.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f, new Item("v.mp4", video, 0, false));
        // il verificatore riceve la lista di una scrittura DIVERSA: nome diverso
        List<StreamingZipWriter.EntryInfo> other;
        File g = new File(tmp, "dir2.zip");
        other = write(g, new Item("altro.mp4", video, 0, false));
        ZipVerifier.Result r = verify(f, other, 0);
        check(t + ": nome diverso (" + r.firstProblem() + ")", !r.ok);
        // numero di voci diverso
        List<StreamingZipWriter.EntryInfo> two = new ArrayList<>(infos);
        two.add(infos.get(0));
        r = verify(f, two, 0);
        check(t + ": numero di voci diverso", !r.ok);
    }

    static void testVerifierPriorityForVideos() throws Exception {
        String t = "con budget ridotto la rilettura profonda parte dai video";
        byte[] photo = videoLike(2_000_000, 13);
        byte[] video = videoLike(2_000_000, 14);
        File f = new File(tmp, "prio.zip");
        List<StreamingZipWriter.EntryInfo> infos = write(f,
                new Item("a.jpg", photo, 0, false), new Item("v.mp4", video, 0, false));
        byte[] all = Files.readAllBytes(f.toPath());
        long videoOffset = infos.get(1).offset + 30 + "v.mp4".length() + 1_000_000;
        long photoOffset = infos.get(0).offset + 30 + "a.jpg".length() + 1_000_000;
        java.util.Set<String> prio = new java.util.HashSet<>(Arrays.asList("v.mp4"));
        long budget = 2_100_000;

        byte[] badVideo = all.clone();
        badVideo[(int) videoOffset] ^= 0x7F;
        File g = new File(tmp, "prio_v.zip");
        Files.write(g.toPath(), badVideo);
        ZipVerifier.Result r = verifyPrio(g, infos, budget, prio);
        check(t + ": alterazione nel video scoperta", !r.ok && r.deepEntries == 0);

        byte[] badPhoto = all.clone();
        badPhoto[(int) photoOffset] ^= 0x7F;
        File h = new File(tmp, "prio_p.zip");
        Files.write(h.toPath(), badPhoto);
        r = verifyPrio(h, infos, budget, prio);
        check(t + ": solo il video è riletto (1 voce), la foto no per budget", r.ok && r.deepEntries == 1);
    }

    static ZipVerifier.Result verifyPrio(File f, List<StreamingZipWriter.EntryInfo> infos, long budget,
                                         java.util.Set<String> prio) throws Exception {
        try (final RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return ZipVerifier.verify(new ZipVerifier.Source() {
                @Override
                public long length() throws IOException {
                    return raf.length();
                }

                @Override
                public int readAt(long position, byte[] buf, int off, int len) throws IOException {
                    raf.seek(position);
                    return raf.read(buf, off, len);
                }
            }, infos, budget, prio);
        }
    }

    static void testNamesUnique() {
        String t = "nomi duplicati resi unici";
        ZipNames n = new ZipNames();
        String a = n.unique("IMG.jpg");
        String b = n.unique("img.JPG");
        String c = n.unique("IMG.jpg");
        check(t + ": " + a + ", " + b + ", " + c, !a.equalsIgnoreCase(b) && !a.equalsIgnoreCase(c) && !b.equalsIgnoreCase(c));
    }

    static void testSniffer() {
        String t = "riconoscimento da intestazione";
        check(t + ": mp4 isom", "video/mp4".equals(sn(box("isom"))));
        check(t + ": mp4 mp42", "video/mp4".equals(sn(box("mp42"))));
        check(t + ": mov", "video/quicktime".equals(sn(box("qt  "))));
        check(t + ": 3gp", "video/3gpp".equals(sn(box("3gp4"))));
        check(t + ": 3g2", "video/3gpp2".equals(sn(box("3g2a"))));
        check(t + ": heic è una foto", "image/heic".equals(sn(box("heic"))));
        check(t + ": avif è una foto", "image/avif".equals(sn(box("avif"))));
        check(t + ": mkv/webm", "video/x-matroska".equals(sn(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0})));
        check(t + ": jpeg", "image/jpeg".equals(sn(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0})));
        check(t + ": png", "image/png".equals(sn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})));
        check(t + ": avi", "video/x-msvideo".equals(sn("RIFF\0\0\0\0AVI LIST".getBytes(StandardCharsets.ISO_8859_1))));
        check(t + ": webp", "image/webp".equals(sn("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))));
        check(t + ": testo non riconosciuto", sn("ciao, non sono una foto".getBytes(StandardCharsets.UTF_8)) == null);
        check(t + ": troppo corto", MediaSniffer.sniff(new byte[]{1, 2}, 2) == null);
        check(t + ": estensioni", "mp4".equals(MediaSniffer.extensionFor("video/mp4")) && "mov".equals(MediaSniffer.extensionFor("video/quicktime")));
        check(t + ": isVideoMime", MediaSniffer.isVideoMime("video/mp4") && !MediaSniffer.isVideoMime("image/jpeg"));
    }

    static void testZip64ThresholdPolicy() {
        String t = "criterio Zip64 per voce";
        check(t + ": dimensione ignota -> Zip64", StreamingZipWriter.needsZip64Entry(-1) && StreamingZipWriter.needsZip64Entry(0));
        check(t + ": 100 MB -> no", !StreamingZipWriter.needsZip64Entry(100_000_000L));
        check(t + ": 1,9 GB -> no", !StreamingZipWriter.needsZip64Entry(1_900_000_000L));
        check(t + ": 2 GB -> sì", StreamingZipWriter.needsZip64Entry(2_000_000_000L));
        check(t + ": 5 GB -> sì", StreamingZipWriter.needsZip64Entry(5_000_000_000L));
    }

    static void testTooBigWithoutZip64() throws Exception {
        String t = "voce senza Zip64 che supera i 4 GB: errore chiaro";
        InputStream endless = new InputStream() {
            long left = 4_400_000_000L;

            @Override
            public int read() {
                return left-- > 0 ? 1 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                left -= n;
                return n;
            }
        };
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void write(byte[] b, int o, int l) {
            }
        };
        StreamingZipWriter w = new StreamingZipWriter(sink, 0);
        boolean threw = false;
        try {
            w.addEntry("enorme.mp4", 0, endless, null, 0, false);
        } catch (IOException e) {
            threw = e.getMessage() != null && e.getMessage().contains("troppo grande");
        }
        check(t, threw);
    }

    // ---- infrastruttura --------------------------------------------------------------------------

    static final class Item {
        final String name;
        final byte[] data;
        final int level;
        final boolean zip64;

        Item(String name, byte[] data, int level, boolean zip64) {
            this.name = name;
            this.data = data;
            this.level = level;
            this.zip64 = zip64;
        }
    }

    static List<StreamingZipWriter.EntryInfo> write(File f, Item... items) throws Exception {
        try (StreamingZipWriter w = new StreamingZipWriter(new FileOutputStream(f), 0)) {
            for (Item it : items) {
                long n = w.addEntry(it.name, 1_700_000_000_000L, new ByteArrayInputStream(it.data), null, it.level, it.zip64);
                if (n != it.data.length) {
                    throw new IllegalStateException("addEntry ha restituito " + n + " invece di " + it.data.length);
                }
            }
            w.finish();
            return w.entryInfos();
        }
    }

    /** Controlla un archivio con tutti i lettori disponibili e confronta il contenuto byte per byte. */
    static void checkEverything(String t, File f, List<StreamingZipWriter.EntryInfo> infos, String[] names, byte[][] data) throws Exception {
        // 1) java.util.zip.ZipFile
        try (ZipFile z = new ZipFile(f)) {
            check(t + ": ZipFile numero voci", z.size() == names.length);
            boolean same = true;
            for (int i = 0; i < names.length; i++) {
                ZipEntry e = z.getEntry(names[i]);
                if (e == null) {
                    same = false;
                    continue;
                }
                byte[] got = z.getInputStream(e).readAllBytes();
                if (!Arrays.equals(got, data[i]) || e.getSize() != data[i].length) {
                    same = false;
                }
            }
            check(t + ": ZipFile contenuto identico all'originale", same);
        } catch (Exception ex) {
            check(t + ": ZipFile apre l'archivio (" + ex + ")", false);
        }
        // 2) unzip -t e estrazione reale con confronto
        if (haveUnzip) {
            check(t + ": unzip -t", run("unzip", "-tq", f.getPath()) == 0);
            File dir = new File(tmp, "x_" + System.nanoTime());
            dir.mkdirs();
            check(t + ": unzip estrae", run("unzip", "-qq", "-o", f.getPath(), "-d", dir.getPath()) == 0);
            boolean same = true;
            for (int i = 0; i < names.length; i++) {
                File x = new File(dir, names[i]);
                if (!x.exists() || !Arrays.equals(Files.readAllBytes(x.toPath()), data[i])) {
                    same = false;
                }
            }
            check(t + ": unzip contenuto identico", same);
            deleteAll(dir);
        }
        // 3) Python
        if (havePython) {
            StringBuilder expect = new StringBuilder();
            check(t + ": python zipfile", run("python3", "-c",
                    "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; assert len(z.namelist())==int(sys.argv[2])",
                    f.getPath(), String.valueOf(names.length)) == 0);
        }
        // 4) il nostro verificatore, con rilettura profonda
        ZipVerifier.Result r = verify(f, infos, 1L << 30);
        check(t + ": ZipVerifier ok " + r.problems, r.ok && r.deepEntries == names.length);
    }

    static ZipVerifier.Result verify(File f, List<StreamingZipWriter.EntryInfo> infos, long budget) throws Exception {
        try (final RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return ZipVerifier.verify(new ZipVerifier.Source() {
                @Override
                public long length() throws IOException {
                    return raf.length();
                }

                @Override
                public int readAt(long position, byte[] buf, int off, int len) throws IOException {
                    raf.seek(position);
                    return raf.read(buf, off, len);
                }
            }, infos, budget);
        }
    }

    /** Flusso che restituisce pezzi di dimensione irregolare (anche 0 byte). */
    static final class OddStream extends InputStream {
        private final byte[] d;
        private final int[] pattern;
        private int pos;
        private int step;

        OddStream(byte[] d, int[] pattern) {
            this.d = d;
            this.pattern = pattern;
        }

        @Override
        public int read() {
            return pos < d.length ? d[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= d.length) {
                return -1;
            }
            int want = pattern[step++ % pattern.length];
            if (want == 0) {
                return 0;
            }
            int n = Math.min(Math.min(want, len), d.length - pos);
            System.arraycopy(d, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** Dati casuali (incomprimibili, come un video) con un'intestazione MP4 credibile. */
    static byte[] videoLike(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        byte[] head = box("isom");
        System.arraycopy(head, 0, b, 0, Math.min(head.length, size));
        return b;
    }

    static byte[] box(String brand) {
        byte[] b = new byte[32];
        b[3] = 0x20;
        b[4] = 'f';
        b[5] = 't';
        b[6] = 'y';
        b[7] = 'p';
        byte[] br = brand.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(br, 0, b, 8, 4);
        return b;
    }

    static String sn(byte[] h) {
        return MediaSniffer.sniff(h, h.length);
    }

    static int run(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("LC_ALL", "C");
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -2;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteAll(k);
            }
        }
        f.delete();
    }
}
