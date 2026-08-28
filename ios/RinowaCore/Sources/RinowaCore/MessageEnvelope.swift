import Foundation

/// 暗号化された封の中に、実際に入るもの。
///
/// `MessageEnvelope.kt` の Swift 側。両方が research/vectors/formats.json に縛られ、
/// 仕様は docs/WIRE_FORMATS.md。
///
/// 本文だけでなく content 全体を封じる理由: 文字しか無かった頃は本文だけで足りたが、
/// 写真を送った瞬間に足りなくなる。id もサイズも**サムネイルも**封の外に出るから。
/// 32px のサムネイルは小さいが、写真そのものには違いない。写真の説明文だけ暗号化して
/// 写真を平文で送るのは、暗号化ではない。
public enum MessageContent: Equatable {
    case text(String)
    case image(Image)
    case video(Video)
    case sticker(String)
    case call(Call)

    public struct Image: Equatable {
        public let mediaId: String
        public let width: Int
        public let height: Int
        public let byteCount: Int
        public let thumbnail: Data
        public let mediaKey: Data?
        public let originalId: String?
        public let originalKey: Data?
        public let originalBytes: Int?
        public let originalMime: String?

        public init(
            mediaId: String,
            width: Int,
            height: Int,
            byteCount: Int,
            thumbnail: Data,
            mediaKey: Data? = nil,
            originalId: String? = nil,
            originalKey: Data? = nil,
            originalBytes: Int? = nil,
            originalMime: String? = nil
        ) {
            self.mediaId = mediaId
            self.width = width
            self.height = height
            self.byteCount = byteCount
            self.thumbnail = thumbnail
            self.mediaKey = mediaKey
            self.originalId = originalId
            self.originalKey = originalKey
            self.originalBytes = originalBytes
            self.originalMime = originalMime
        }
    }

    public struct Video: Equatable {
        public let mediaId: String
        public let width: Int
        public let height: Int
        public let durationMs: Int64
        public let byteCount: Int
        /// **暗号化後**のオブジェクトの大きさ。範囲で読む再生側が最初に必要とする。
        public let sealedBytes: Int64
        public let thumbnail: Data
        public let mediaKey: Data?

        public init(
            mediaId: String,
            width: Int,
            height: Int,
            durationMs: Int64,
            byteCount: Int,
            sealedBytes: Int64,
            thumbnail: Data,
            mediaKey: Data? = nil
        ) {
            self.mediaId = mediaId
            self.width = width
            self.height = height
            self.durationMs = durationMs
            self.byteCount = byteCount
            self.sealedBytes = sealedBytes
            self.thumbnail = thumbnail
            self.mediaKey = mediaKey
        }
    }

    public struct Call: Equatable {
        public enum Outcome: String {
            case completed, missed, declined, failed
        }

        public let video: Bool
        public let outcome: Outcome
        public let seconds: Int

        public init(video: Bool, outcome: Outcome, seconds: Int) {
            self.video = video
            self.outcome = outcome
            self.seconds = seconds
        }
    }
}

public enum MessageEnvelope {

    /// 封じる平文として `content` を書き出す。
    public static func seal(_ content: MessageContent) -> String {
        var object: [String: Any]

        switch content {
        case let .text(body):
            object = [Key.type: Kind.text, Key.body: body]

        case let .image(image):
            object = [
                Key.type: Kind.image,
                Key.mediaId: image.mediaId,
                Key.width: image.width,
                Key.height: image.height,
                Key.bytes: image.byteCount,
                Key.thumbnail: image.thumbnail.base64EncodedString()
            ]
            image.mediaKey.map { object[Key.mediaKey] = $0.base64EncodedString() }
            image.originalId.map { object[Key.originalId] = $0 }
            image.originalKey.map { object[Key.originalKey] = $0.base64EncodedString() }
            image.originalBytes.map { object[Key.originalBytes] = $0 }
            image.originalMime.map { object[Key.originalMime] = $0 }

        case let .video(video):
            object = [
                Key.type: Kind.video,
                Key.mediaId: video.mediaId,
                Key.width: video.width,
                Key.height: video.height,
                Key.durationMs: video.durationMs,
                Key.bytes: video.byteCount,
                Key.sealedBytes: video.sealedBytes,
                Key.thumbnail: video.thumbnail.base64EncodedString()
            ]
            video.mediaKey.map { object[Key.mediaKey] = $0.base64EncodedString() }

        case let .sticker(id):
            object = [Key.type: Kind.sticker, Key.stickerId: id]

        case let .call(call):
            object = [
                Key.type: Kind.call,
                Key.callVideo: call.video,
                Key.callOutcome: call.outcome.rawValue,
                Key.callSeconds: call.seconds
            ]
        }

        guard
            let data = try? JSONSerialization.data(withJSONObject: object),
            let text = String(data: data, encoding: .utf8)
        else {
            // 上の値はすべて String、Int、Bool、または Data 由来の String なので、
            // ここには来ない。落とさずに本文を返すことで、直列化の予想外がメッセージを
            // 道連れにしないようにする。
            if case let .text(body) = content { return body }
            return ""
        }
        return text
    }

    /// 復号された平文を content に戻す。例外は投げない。
    ///
    /// 解釈できないものは文字として返る。この形式ができる前に書かれたメッセージ
    /// （JSON ではない素の本文）が、いまも読めるのはそのため。
    public static func open(_ plaintext: String) -> MessageContent? {
        guard
            let data = plaintext.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return .text(plaintext)
        }

        switch object[Key.type] as? String {
        case Kind.text:
            return .text(object[Key.body] as? String ?? "")

        case Kind.image:
            return .image(
                MessageContent.Image(
                    mediaId: object[Key.mediaId] as? String ?? "",
                    width: intValue(object[Key.width]),
                    height: intValue(object[Key.height]),
                    byteCount: intValue(object[Key.bytes]),
                    thumbnail: decode(object[Key.thumbnail]) ?? Data(),
                    mediaKey: decode(object[Key.mediaKey]),
                    originalId: object[Key.originalId] as? String,
                    originalKey: decode(object[Key.originalKey]),
                    originalBytes: (object[Key.originalBytes] as? NSNumber)?.intValue,
                    originalMime: object[Key.originalMime] as? String
                )
            )

        case Kind.video:
            return .video(
                MessageContent.Video(
                    mediaId: object[Key.mediaId] as? String ?? "",
                    width: intValue(object[Key.width]),
                    height: intValue(object[Key.height]),
                    durationMs: Int64(intValue(object[Key.durationMs])),
                    byteCount: intValue(object[Key.bytes]),
                    sealedBytes: Int64(intValue(object[Key.sealedBytes])),
                    thumbnail: decode(object[Key.thumbnail]) ?? Data(),
                    mediaKey: decode(object[Key.mediaKey])
                )
            )

        case Kind.sticker:
            return .sticker(object[Key.stickerId] as? String ?? "")

        case Kind.call:
            let outcome = (object[Key.callOutcome] as? String)
                .flatMap(MessageContent.Call.Outcome.init(rawValue:)) ?? .completed
            return .call(
                MessageContent.Call(
                    video: object[Key.callVideo] as? Bool ?? false,
                    outcome: outcome,
                    seconds: intValue(object[Key.callSeconds])
                )
            )

        default:
            // 解釈できる JSON だが封ではない。誰かがそう打っただけ。やはりただの文字。
            return .text(plaintext)
        }
    }

    private static func decode(_ value: Any?) -> Data? {
        guard let text = value as? String, !text.isEmpty else { return nil }
        // 途中で切れているか壊れていれば、投げずに空として復号する。空のサムネイルは
        // 仮画像になるだけだが、例外はスレッドの残り全部を失う。
        return Data(base64Encoded: text)
    }

    private static func intValue(_ value: Any?) -> Int {
        (value as? NSNumber)?.intValue ?? 0
    }

    private enum Kind {
        static let text = "text"
        static let image = "image"
        static let video = "video"
        static let sticker = "sticker"
        static let call = "call"
    }

    private enum Key {
        static let type = "t"
        static let body = "b"
        static let mediaId = "id"
        static let mediaKey = "k"
        static let width = "w"
        static let height = "h"
        static let bytes = "n"
        static let thumbnail = "th"
        static let durationMs = "ms"
        static let sealedBytes = "sn"
        static let originalId = "oid"
        static let originalKey = "ok"
        static let originalBytes = "on"
        static let originalMime = "om"
        static let stickerId = "s"
        static let callVideo = "v"
        static let callOutcome = "o"
        static let callSeconds = "sec"
    }
}
