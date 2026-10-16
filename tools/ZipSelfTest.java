import com.zipfoto.app.zip.StreamingZipWriter;
import com.zipfoto.app.zip.ZipNames;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Self-test del writer ZIP (gira su una JVM normale, non serve Android).
 *
 *   javac -d out app/src/main/java/com/zipfoto/app/zip/*.java tools/ZipSelfTest.java
 *   java -cp out ZipSelfTest small /tmp/small.zip 0   /tmp/small.manifest
 *   java -cp out ZipSelfTest big   /tmp/big.zip   0   /tmp/big.manifest
 *   python3 tools/verify_zip.py /tmp/big.zip /tmp/big.manifest
 */
public class ZipSelfTest {
    public static void main(String[] a) throws Exception {
        String mode = a[0];
        File zip = new File(a[1]);
        int level = Integer.parseInt(a[2]);
        File manifest = new File(a[3]);

        Random rnd = new Random(42);
        ZipNames names = new ZipNames();
        long total = 0;
        int count;

        try (StreamingZipWriter w = new StreamingZipWriter(
                new BufferedOutputStream(new FileOutputStream(zip), 1 << 20), level);
             PrintWriter man = new PrintWriter(new OutputStreamWriter(new FileOutputStream(manifest), StandardCharsets.UTF_8))) {

            if (mode.equals("small")) {
                String[] special = {"IMG_0001.jpg", "IMG_0001.jpg", "img_0001.JPG", "Vacanze/Mare/foto è ñ 日本.heic", "vuoto.jpg"};
                for (int i = 0; i < 300; i++) {
                    String name = i < special.length ? special[i] : "album/" + (i % 7) + "/IMG_" + i + ".jpg";
                    int size = (i == 4) ? 0 : 1 + rnd.nextInt(3_000_000);
                    byte[] data = new byte[size];
                    rnd.nextBytes(data);
                    if (i % 3 == 0 && size > 1000) {            // parte compressibile per testare level > 0
                        java.util.Arrays.fill(data, 0, size / 2, (byte) 7);
                    }
                    String u = names.unique(name);
                    CRC32 c = new CRC32();
                    c.update(data, 0, data.length);
                    w.addEntry(u, 1_700_000_000_000L + i * 1000L, new ByteArrayInputStream(data), null);
                    man.println(u + "\t" + c.getValue() + "\t" + size);
                    total += size;
                }
            } else {
                // ~4,8 GB in 240 file da 20 MB: gli offset superano i 4 GiB (serve Zip64)
                byte[] base = new byte[20 * 1024 * 1024];
                rnd.nextBytes(base);
                for (int i = 0; i < 240; i++) {
                    base[0] = (byte) i;
                    base[1] = (byte) (i >> 8);
                    String u = names.unique("DCIM/Camera/PXL_" + i + ".jpg");
                    CRC32 c = new CRC32();
                    c.update(base, 0, base.length);
                    w.addEntry(u, 1_700_000_000_000L, new ByteArrayInputStream(base), null);
                    man.println(u + "\t" + c.getValue() + "\t" + base.length);
                    total += base.length;
                }
            }
            count = w.entryCount();
            w.finish();
            System.out.println("entries=" + count + " input=" + total + " archive=" + w.bytesWritten());
        }
    }
}
