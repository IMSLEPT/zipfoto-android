# Zippix (ex ZipFoto)

App Android per mettere centinaia di foto **e video** (500 file e oltre) in un solo file ZIP **senza perdere qualità**.
Il nome dell'app è Zippix; il pacchetto resta `com.zipfoto.app`, così l'APK si installa come aggiornamento sopra le versioni precedenti.

## Come funziona la qualità

Due modalità, scelte nella schermata di selezione:

**Originale (predefinita, senza perdita)**
- Ogni foto e ogni video viene copiato **byte per byte** nello ZIP: nessuna ricompressione, nessun ridimensionamento.
- Di default usa DEFLATE a livello 0 (blocchi "stored"): foto JPEG/HEIC e video sono già compressi, quindi comprimere ancora farebbe solo perdere tempo. Lo switch "Compressione extra" abilita DEFLATE livello 6 per le foto (sempre senza perdita, ma guadagno minimo). I video non vengono mai ricompressi.

**Ridotte (con perdita, per email e per l'analisi con Copilot)**
- Ridimensiona il lato lungo (mai ingrandisce) e ricodifica in JPEG. L'orientamento EXIF è applicato ai pixel; il JPEG risultante non contiene EXIF/GPS.
- Livelli: *Leggera* 1400 px q75 (≈0,2 MB/foto), *Media* 1600 px q80 (≈0,3 MB/foto), *Alta* 2000 px q85 (≈0,5 MB/foto, per scritte a mano e testo piccolo). I pesi sono medie misurate su 49 foto di schermate e un foglio manoscritto: dipendono dal soggetto.
- Se la versione ridotta non è più leggera dell'originale, o la foto non è decodificabile, nello ZIP resta l'originale. Le GIF non vengono toccate.
- **I video restano sempre originali**, in qualsiasi modalità (non vengono ricodificati).
- Gmail accetta 25 MB totali di allegati per email; la schermata finale avvisa sopra ~18 MB.

Il writer ZIP (`StreamingZipWriter.java`) scrive in streaming con memoria costante e supporta **Zip64**: archivi oltre 4 GB, oltre 65 535 file e, per singola voce, video oltre 4 GB (le voci da 2 GB in su, o di dimensione ignota, sono scritte in Zip64). Il lavoro gira in un servizio in primo piano: continua con lo schermo bloccato.

### Come si evita un "ZIP riuscito" che non lo è

- **Foto o video si decide dai byte**, non solo da nome e tipo dichiarato (`MediaSniffer.java`): un video senza estensione o con tipo generico resta un video e riceve l'estensione giusta (`.mp4`, `.mov`…).
- **Mai file saltati in silenzio**: se un file non si apre o è vuoto, la schermata finale lo elenca con il motivo.
- **Controllo finale**: a lavoro finito lo ZIP viene riaperto e riletto da un lettore indipendente dal writer (`ZipVerifier.java`): directory centrale, intestazioni, data descriptor e, per i video per primi, decompressione e CRC confrontati con i byte letti dalla sorgente. Se la destinazione non si può rileggere (alcuni provider cloud), la schermata dice "controllo non eseguito" invece di dichiarare successo.
- La schermata finale elenca i video effettivamente scritti con la loro dimensione.

## Uso

1. Scegli cosa archiviare:
   - **Foto e video**: selettore foto di sistema, mostra la Galleria e **Google Foto**. Il sistema fissa un tetto di elementi per selezione: si può ripetere, i doppioni vengono ignorati.
   - **Dai file**: Drive, Download e altre app di archiviazione, senza limite di quantità.
   - **Una cartella** (sottocartelle incluse).
   - oppure dalla Galleria o da Google Foto: *Condividi → Zippix*.
2. Scegli *Originale* o un livello ridotto, dai un nome, scegli dove salvare (Download, Drive, ecc.), tocca **Crea ZIP**.
3. Al termine puoi condividere lo ZIP.

## Build

L'APK viene compilato da GitHub Actions a ogni push su `main` (workflow `Build APK`) e pubblicato in *Releases*.
In locale: `./gradlew assembleRelease` (serve Android SDK 35 e JDK 17).

## Test della catena ZIP

Si eseguono su una JVM qualsiasi (girano anche in CI prima della build). Ogni archivio è riletto da `java.util.zip`, `unzip -t` e Python, oltre che da `ZipVerifier`:

```
javac -d build/ziptest app/src/main/java/com/zipfoto/app/zip/*.java tools/ZipDebugTest.java
LC_ALL=C.UTF-8 java -cp build/ziptest ZipDebugTest
```

Copre: video da 24 MB copiato senza ricompressione, file vuoto, nomi UTF-8 e sottocartelle, voce Zip64 forzata su file piccoli, sorgenti con letture a pezzi minuscoli o vuoti, DEFLATE, 70.000 voci (Zip64 d'archivio), file troncato / dati alterati / directory diversa (il verificatore deve accorgersene), riconoscimento dei formati dai byte.

### Test dei grandi archivi

```
javac -d out app/src/main/java/com/zipfoto/app/zip/*.java tools/ZipSelfTest.java
java -cp out ZipSelfTest big /tmp/big.zip 0 /tmp/big.manifest      # ~4,8 GB, forza Zip64
python3 tools/verify_zip.py /tmp/big.zip /tmp/big.manifest

java -cp out ZipSelfTest mixed /tmp/mixed.zip 0 /tmp/mixed.manifest   # voci Zip64 e normali, livelli misti
python3 tools/verify_zip.py /tmp/mixed.zip /tmp/mixed.manifest
unzip -t /tmp/mixed.zip
```

## Grafica

Lo stile dell'interfaccia deriva dall'icona: blu notte `#021A5F`, zip in cobalto/ciano (`#0086FD`, `#01B7FE`, `#004EDB`), superfici "carta" chiare con bordo bianco, gradiente arancio → magenta → viola per foto e video. L'icona è adattiva: il disegno sta dentro la zona sicura (68 dp su 108), così nessuna maschera del launcher (cerchio, squircle, rettangolo arrotondato) taglia i bordi.

## Note

- `app/zipfoto.jks` è la chiave di firma personale (repo privato): serve a poter aggiornare l'app sopra la versione già installata. Per il Play Store andrà usata una chiave dedicata.
- Font: Poppins (SIL OFL, vedi `licenses/`).
