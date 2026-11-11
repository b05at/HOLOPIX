package at.b05.holopix.screensaver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.b05.holopix.Holo
import at.b05.holopix.data.SmbSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diashow-Ablauf: Fotoliste (Cache bzw. neu einlesen), dann Collage für Collage –
 * die nächste wird schon geladen, während die aktuelle zu sehen ist.
 */
class SlideShow(private val scope: CoroutineScope) {
    var slide by mutableStateOf<Slide?>(null)
        private set
    /** Was gerade passiert / warum keine Fotos kommen. */
    var status by mutableStateOf<String?>(null)
        private set

    /** Aktueller Bildschirm (wird bei jeder Collage neu abgefragt – Drehen/anderes Gerät passt sich an). */
    var screen: () -> ScreenProfile = { ScreenProfile(1600, 1000, 2f) }

    private var job: Job? = null
    private val collage get() = Holo.collage
    private val photos get() = Holo.photos
    private val prefs get() = Holo.prefs

    fun start() {
        job?.cancel()
        if (!photos.ready) {
            slide = null
            status = "Keine Fotoquelle eingerichtet – in HOLOPIX einen Ordner oder eine SMB-Freigabe wählen"
            return
        }
        job = scope.launch {
            try {
                if (collage.photoCount == 0) collage.loadCache()
                if (collage.photoCount == 0 || collage.cacheStale) {
                    if (collage.photoCount == 0) status = "Suche Fotos …"
                    var scanError: String? = null
                    val scan = launch {
                        try {
                            collage.refresh { n -> if (slide == null) status = "Suche Fotos … $n gefunden" }
                        } catch (e: CancellationException) { throw e } catch (e: Throwable) { scanError = SmbSource.explain(e) }
                    }
                    while (collage.photoCount == 0 && scan.isActive) delay(500)
                    if (collage.photoCount == 0) {
                        scan.join()
                        if (collage.photoCount == 0) {
                            status = scanError?.let { "Fehler: $it" } ?: "Keine Fotos (jpg, png, webp, heic) gefunden"
                            return@launch
                        }
                    }
                }
                if (slide == null) status = "Lade Fotos …"
                var failures = 0
                val progress: (String) -> Unit = { p -> if (slide == null) status = "Lade Fotos …\n$p" }
                // erstes Bild schnell (höchstens 2 Fotos), danach volle Collagen
                var forScreen = screen()
                var pending = next { collage.nextSlide(forScreen, maxPhotos = 2, onProgress = progress) }
                while (isActive) {
                    val n = withTimeoutOrNull(120_000) { pending.await() }
                    if (n == null && pending.isActive) {
                        pending.cancel()
                        photos.lastError = (photos.lastError ?: "") + " (Zeitüberschreitung nach 2 Minuten)"
                    }
                    forScreen = screen()
                    val target = forScreen
                    pending = next { collage.nextSlide(target, onProgress = progress) }
                    if (n != null) {
                        slide = n; failures = 0; status = null
                        // warten – wird der Bildschirm gedreht, sofort eine passende neue Collage bauen
                        val until = System.currentTimeMillis() + prefs.collageSeconds.coerceAtLeast(5) * 1000L
                        while (System.currentTimeMillis() < until) {
                            delay(500)
                            val now = screen()
                            if (kotlin.math.abs(now.aspect - target.aspect) > 0.05) {
                                pending.cancel()
                                forScreen = now
                                pending = next { collage.nextSlide(now, maxPhotos = 2, onProgress = progress) }
                                break
                            }
                        }
                    } else {
                        failures++
                        if (slide == null) status = "Lade Fotos … (${failures}. Versuch)" + (photos.lastError?.let { "\nLetzter Fehler: $it" } ?: "")
                        if (failures >= 10) {
                            status = "Fotos konnten nicht geladen werden.\n" + (photos.lastError ?: "Unbekannter Fehler") + "\nNeuer Versuch in 1 Minute."
                            delay(60_000); failures = 0
                            pending.cancel()
                            forScreen = screen()
                            val s2 = forScreen
                            pending = next { collage.nextSlide(s2, maxPhotos = 2, onProgress = progress) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "Fehler: " + SmbSource.explain(e)
            }
        }
    }

    /** Nächste Collage im Hintergrund – ein Fehler darin darf die App nicht beenden. */
    private fun CoroutineScope.next(block: suspend () -> Slide?) = async {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            collage.trimMemory(); System.gc()
            photos.lastError = "Zu wenig Speicher für dieses Foto"
            null
        } catch (e: Throwable) {
            photos.lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            null
        }
    }

    fun stop() {
        job?.cancel(); job = null
        slide = null; status = null
    }
}
