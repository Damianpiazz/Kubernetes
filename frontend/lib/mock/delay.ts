/** Latencia simulada para el modo mock, con jitter para no falsear el cache. */
export function delay(minMs = 300, maxMs = 700): Promise<void> {
  const ms = minMs + Math.random() * (maxMs - minMs)
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Latencia de operaciones "pesadas" (checkout, login), un poco más larga. */
export function slowDelay(): Promise<void> {
  return delay(600, 1100)
}
