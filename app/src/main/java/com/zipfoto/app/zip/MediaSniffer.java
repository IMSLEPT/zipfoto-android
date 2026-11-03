package com.zipfoto.app.zip;

/**
 * Riconosce foto e video dai primi byte del file ("magic number"), senza dipendere da tipo MIME ed estensione:
 * alcuni provider (Google Foto, Drive, app di terze parti) restituiscono nomi senza estensione o tipi generici.
 * Classe pura Java: si testa sulla JVM.
 */
public final class MediaSniffer {

    private MediaSniffer() {
    }

    /** Quanti byte iniziali servono al riconoscimento. */
    public static final int HEAD_SIZE = 64;

    /**
     * @return il tipo MIME riconosciuto (es. "video/mp4", "image/jpeg"), oppure null se il formato non è noto.
     */
    public static String sniff(byte[] h, int len) {
        if (h == null || len < 4) {
            return null;
        }
        // JPEG
        if (u(h, 0) == 0xFF && u(h, 1) == 0xD8 && u(h, 2) == 0xFF) {
            return "image/jpeg";
        }
        // PNG
        if (u(h, 0) == 0x89 && u(h, 1) == 'P' && u(h, 2) == 'N' && u(h, 3) == 'G') {
            return "image/png";
        }
        // GIF
        if (u(h, 0) == 'G' && u(h, 1) == 'I' && u(h, 2) == 'F' && u(h, 3) == '8') {
            return "image/gif";
        }
        // TIFF (e molti RAW basati su TIFF)
        if ((u(h, 0) == 'I' && u(h, 1) == 'I' && u(h, 2) == 0x2A && u(h, 3) == 0x00)
                || (u(h, 0) == 'M' && u(h, 1) == 'M' && u(h, 2) == 0x00 && u(h, 3) == 0x2A)) {
            return "image/tiff";
        }
        // Matroska / WebM
        if (u(h, 0) == 0x1A && u(h, 1) == 0x45 && u(h, 2) == 0xDF && u(h, 3) == 0xA3) {
            return "video/x-matroska";
        }
        // FLV
        if (u(h, 0) == 'F' && u(h, 1) == 'L' && u(h, 2) == 'V' && u(h, 3) == 0x01) {
            return "video/x-flv";
        }
        // MPEG program stream
        if (u(h, 0) == 0x00 && u(h, 1) == 0x00 && u(h, 2) == 0x01 && u(h, 3) == 0xBA) {
            return "video/mpeg";
        }
        // ASF / WMV
        if (len >= 8 && u(h, 0) == 0x30 && u(h, 1) == 0x26 && u(h, 2) == 0xB2 && u(h, 3) == 0x75
                && u(h, 4) == 0x8E && u(h, 5) == 0x66 && u(h, 6) == 0xCF && u(h, 7) == 0x11) {
            return "video/x-ms-wmv";
        }
        // RIFF: AVI o WebP
        if (len >= 12 && u(h, 0) == 'R' && u(h, 1) == 'I' && u(h, 2) == 'F' && u(h, 3) == 'F') {
            if (u(h, 8) == 'A' && u(h, 9) == 'V' && u(h, 10) == 'I' && u(h, 11) == ' ') {
                return "video/x-msvideo";
            }
            if (u(h, 8) == 'W' && u(h, 9) == 'E' && u(h, 10) == 'B' && u(h, 11) == 'P') {
                return "image/webp";
            }
            return null;
        }
        // ISO BMFF (MP4, MOV, 3GP, HEIC, AVIF): "ftyp" in posizione 4
        if (len >= 12 && u(h, 4) == 'f' && u(h, 5) == 't' && u(h, 6) == 'y' && u(h, 7) == 'p') {
            String brand = new String(new char[]{(char) u(h, 8), (char) u(h, 9), (char) u(h, 10), (char) u(h, 11)});
            return fromBrand(brand);
        }
        return null;
    }

    private static String fromBrand(String brand) {
        switch (brand) {
            case "qt  ":
                return "video/quicktime";
            case "heic":
            case "heix":
            case "heim":
            case "heis":
            case "hevc":
            case "hevx":
            case "hevm":
            case "hevs":
            case "mif1":
            case "msf1":
                return "image/heic";
            case "avif":
            case "avis":
                return "image/avif";
            default:
                break;
        }
        if (brand.startsWith("3g2")) {
            return "video/3gpp2";
        }
        if (brand.startsWith("3g")) {
            return "video/3gpp";
        }
        // isom, iso2, mp41, mp42, avc1, M4V , dash, MSNV, ecc.: tutti MP4
        return "video/mp4";
    }

    public static boolean isVideoMime(String mime) {
        return mime != null && mime.startsWith("video/");
    }

    /** Estensione (senza punto) adatta al tipo MIME riconosciuto, o null. */
    public static String extensionFor(String mime) {
        if (mime == null) {
            return null;
        }
        switch (mime) {
            case "video/mp4":
                return "mp4";
            case "video/quicktime":
                return "mov";
            case "video/3gpp":
                return "3gp";
            case "video/3gpp2":
                return "3g2";
            case "video/x-matroska":
                return "mkv";
            case "video/x-flv":
                return "flv";
            case "video/mpeg":
                return "mpg";
            case "video/x-ms-wmv":
                return "wmv";
            case "video/x-msvideo":
                return "avi";
            case "image/jpeg":
                return "jpg";
            case "image/png":
                return "png";
            case "image/gif":
                return "gif";
            case "image/tiff":
                return "tif";
            case "image/webp":
                return "webp";
            case "image/heic":
                return "heic";
            case "image/avif":
                return "avif";
            default:
                return null;
        }
    }

    private static int u(byte[] b, int i) {
        return b[i] & 0xFF;
    }
}
