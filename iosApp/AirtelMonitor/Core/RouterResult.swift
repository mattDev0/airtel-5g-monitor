import Foundation

/// Typed outcome of any router RPC or settings mutation, aligned with Android's `RouterResult`.
public enum RouterResult<T> {
    case ok(T)
    case notLoggedIn(String = "Authentication required")
    case limitedAccess(cmd: Int, message: String = "This router account lacks permission for this setting")
    case unreachable(String = "Router is unreachable")
    case badResponse(message: String, rawBody: String = "")

    public var isOk: Bool {
        if case .ok = self { return true }
        return false
    }

    public var value: T? {
        if case .ok(let v) = self { return v }
        return nil
    }

    public func getOrNull() -> T? { value }

    public var errorMessage: String? {
        switch self {
        case .ok:
            return nil
        case .notLoggedIn(let msg):
            return msg
        case .limitedAccess(_, let msg):
            return msg
        case .unreachable(let msg):
            return msg
        case .badResponse(let msg, _):
            return msg
        }
    }

    public func map<U>(_ transform: (T) -> U) -> RouterResult<U> {
        switch self {
        case .ok(let value):
            return .ok(transform(value))
        case .notLoggedIn(let msg):
            return .notLoggedIn(msg)
        case .limitedAccess(let cmd, let msg):
            return .limitedAccess(cmd: cmd, message: msg)
        case .unreachable(let msg):
            return .unreachable(msg)
        case .badResponse(let msg, let raw):
            return .badResponse(message: msg, rawBody: raw)
        }
    }

    public func castFailure<U>() -> RouterResult<U> {
        switch self {
        case .ok:
            preconditionFailure("Cannot cast .ok without value")
        case .notLoggedIn(let msg):
            return .notLoggedIn(msg)
        case .limitedAccess(let cmd, let msg):
            return .limitedAccess(cmd: cmd, message: msg)
        case .unreachable(let msg):
            return .unreachable(msg)
        case .badResponse(let msg, let raw):
            return .badResponse(message: msg, rawBody: raw)
        }
    }
}
