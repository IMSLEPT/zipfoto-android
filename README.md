# ZipFoto

App Android per mettere centinaia di foto (500 e oltre) in un solo file ZIP **senza perdere qualità**.

## Come funziona la qualità

Due modalità, scelte nella schermata di selezione:

**Originale (predefinita, senza perdita)**
- Ogni foto viene copiata **byte per byte** nello ZIP: nessuna ricompressione dell'immagine, nessun ridimensionamento.
- Di default usa DEFLATE a livello 0 (blocchi "stored"): le foto JPEG/HEIC sono già compresse, quindi comprimere ancora farebbe solo perdere tempo. Lo switch "Compressione extra" abilita DEFLATE livello 6 (sempre senza perdita, ma guadagno minimo).

**Ridotte (con perdita, per email e per l'analisi con Copilot)**
- Ridimensiona il lato lungo (mai ingrandisce) e ricodifica in JPEG. L'orientamento EXIF è applicato ai pixel; il JPEG risultante non contiene EXIF/GPS.
- Livelli: *Leggera* 1400 px q75 (≈0,2 MB/foto), *Media* 1600 px q80 (≈0,3 MB/foto), *Alta* 2000 px q85 (≈0,5 MB/foto, per scritte a mano e testo piccolo). I pesi sono medie misurate su 49 foto di schermate e un foglio manoscritto: dipendono dal soggetto.
- Se la versione ridotta non è più leggera dell'originale, o la foto non è decodificabile, nello ZIP resta l'originale. Le GIF non vengono toccate.
- Gmail accetta 25 MB totali di allegati per email; la schermata finale avvisa sopra ~18 MB.

Il writer ZIP (`StreamingZipWriter.java`) scrive in streaming con memoria costante e supporta **Zip64**: archivi oltre 4 GB e oltre 65 535 file. Il lavoro gira in un servizio in primo piano: continua con lo schermo bloccato.

## Uso

1. **Scegli le foto** (selettore documenti, nessun limite di quantità) o **Scegli una cartella** (sottocartelle incluse), oppure dalla Galleria: *Condividi → ZipFoto*.
2. Scegli *Originale* o un livello ridotto, dai un nome, scegli dove salvare (Download, Drive, ecc.), tocca **Crea ZIP**.
3. Al termine puoi condividere lo ZIP.

## Build

L'APK viene compilato da GitHub Actions a ogni push su `main` (workflow `Build APK`) e pubblicato in *Releases*.
In locale: `./gradlew assembleRelease` (serve Android SDK 35 e JDK 17).

## Test del writer ZIP

```
javac -d out app/src/main/java/com/zipfoto/app/zip/*.java tools/ZipSelfTest.java
java -cp out ZipSelfTest big /tmp/big.zip 0 /tmp/big.manifest      # ~4,8 GB, forza Zip64
python3 tools/verify_zip.py /tmp/big.zip /tmp/big.manifest
```

## Note

- `app/zipfoto.jks` è la chiave di firma personale (repo privato): serve a poter aggiornare l'app sopra la versione già installata. Per il Play Store andrà usata una chiave dedicata.
- Font: Poppins (SIL OFL, vedi `licenses/`).
