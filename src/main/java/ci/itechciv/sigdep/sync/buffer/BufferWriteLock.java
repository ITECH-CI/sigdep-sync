package ci.itechciv.sigdep.sync.buffer;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Verrou d'écriture applicatif du buffer SQLite, partagé par tous les écrivains
 * (outbox + sync_state).
 *
 * <p>Depuis le pipeline découplé, deux threads écrivent en concurrence sur le
 * buffer : le producteur ({@code enqueueBatch}) et le consommateur
 * ({@code markSent}/{@code markRejected}/{@code updateWatermark}). SQLite
 * n'autorise qu'un seul écrivain à la fois ; une transaction d'enqueue longue
 * (backfill massif — p. ex. 5000+ lab_results en une transaction) peut tenir le
 * verrou d'écriture plus longtemps que le {@code busy_timeout}, et l'autre
 * écrivain échoue alors sur {@code SQLITE_BUSY} (« database is locked »).</p>
 *
 * <p>Ce verrou <b>sérialise</b> les écritures côté application : deux écritures
 * ne se chevauchent jamais, quelle que soit la durée d'une transaction. Il
 * élimine la cause racine plutôt que de la masquer par un timeout. Les lectures
 * ne sont pas verrouillées (WAL permet lecteurs + un écrivain concurrents).</p>
 *
 * <p>Réentrant : une méthode déjà sous verrou peut en appeler une autre sans
 * interblocage.</p>
 */
@Component
public class BufferWriteLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** Exécute une écriture sous verrou exclusif du buffer. */
    public void runExclusively(Runnable write) {
        lock.lock();
        try {
            write.run();
        } finally {
            lock.unlock();
        }
    }

    /** Variante retournant une valeur (pour les écritures qui produisent un résultat). */
    public <T> T callExclusively(java.util.function.Supplier<T> write) {
        lock.lock();
        try {
            return write.get();
        } finally {
            lock.unlock();
        }
    }
}
