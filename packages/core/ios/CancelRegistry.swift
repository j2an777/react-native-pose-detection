import Foundation

/// Cancel flags for video detection and export, keyed by the task id JavaScript minted.
final class CancelRegistry {
  private let lock = NSLock()
  private var cancelled = [Int: Bool]()

  /// Called when the job is queued and again when it starts, so a cancel in between is kept.
  func begin(_ taskId: Int) {
    lock.lock()
    defer { lock.unlock() }
    if cancelled[taskId] == nil {
      cancelled[taskId] = false
    }
  }

  func end(_ taskId: Int) {
    lock.lock()
    defer { lock.unlock() }
    cancelled.removeValue(forKey: taskId)
  }

  /// Ignored unless the task is running: JS ids never reset, so remembered cancels would pile up.
  func cancel(_ taskId: Int) {
    lock.lock()
    defer { lock.unlock() }
    if cancelled[taskId] != nil {
      cancelled[taskId] = true
    }
  }

  func isCancelled(_ taskId: Int) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return cancelled[taskId] == true
  }
}
