import Foundation

/// Swift's stand-in for Kotlin's `@Volatile`, for values touched a few times per frame; anything
/// per-landmark stays confined to one thread instead.
final class Guarded<Value> {
  private let lock = NSLock()
  private var storage: Value

  init(_ value: Value) {
    self.storage = value
  }

  var value: Value {
    get {
      lock.lock()
      defer { lock.unlock() }
      return storage
    }
    set {
      lock.lock()
      storage = newValue
      lock.unlock()
    }
  }

  /// Atomic read-modify-write: `value += 1` locks twice and can lose an increment.
  func mutate<Result>(_ body: (inout Value) -> Result) -> Result {
    lock.lock()
    defer { lock.unlock() }
    return body(&storage)
  }
}
