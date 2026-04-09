package motif;

/**
 * Lightweight lock object used for per-dependency synchronization in Motif scopes.
 *
 * <p>This class is intentionally minimal to reduce memory overhead. Each instance
 * only contains the Java object header (12-16 bytes on most JVMs) with no additional
 * fields or methods.
 *
 * <p>Used as the lock target in {@code synchronized(lock)} blocks for cached
 * dependencies when {@link MotifRuntimeConfig#usePerDependencyLock} is enabled.
 */
public final class MotifLock {
  // Intentionally empty - just used for synchronization
  // Minimal memory footprint: object header only
}
