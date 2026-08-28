import Crypto
import Foundation
import _CryptoExtras

/// バックアップの錠。`BackupCipher.kt` の Swift 側。
///
/// 配置も計算量も関連データも docs/WIRE_FORMATS.md §2 にあり、両方の実装が
/// research/vectors/formats.json に縛られている。**片方が書いたバックアップは、
/// もう片方で開く必要がある**。それが分かる頃には、元の端末はたいてい無い。
///
/// ```
/// "LOWANBK" 0x01   8バイト   識別子と版
/// iterations       4バイト   ビッグエンディアン
/// salt            16バイト
/// nonce           12バイト
/// ciphertext       nバイト   AES-256-GCM、タグ込み
/// ```
///
/// 暗号文より前の全部を関連データとして結び付ける。既存ファイルの反復回数を下げる
/// （総当たりを安くする一番わかりやすい手）と、弱いファイルができるのではなく認証に失敗する。
public enum BackupCipher {

    /// 端末で約1秒。Android 側と意図的に揃えてある。
    public static let iterations = 600_000

    public static func seal(_ plaintext: Data, secret: String) throws -> Data {
        precondition(!secret.isEmpty, "バックアップの暗証番号が空です")

        var salt = Data(count: saltBytes)
        salt.withUnsafeMutableBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            var random = SystemRandomNumberGenerator()
            for offset in 0..<buffer.count {
                base.advanced(by: offset)
                    .assumingMemoryBound(to: UInt8.self)
                    .pointee = UInt8.random(in: 0...255, using: &random)
            }
        }

        let nonce = AES.GCM.Nonce()
        let header = self.header(iterations: iterations, salt: salt, nonce: Data(nonce))
        let key = try derive(secret: secret, salt: salt, iterations: iterations)

        let box = try AES.GCM.seal(
            plaintext,
            using: key,
            nonce: nonce,
            authenticating: header
        )
        return header + box.ciphertext + box.tag
    }

    /// バックアップを開く。開かなければ nil。
    ///
    /// nil は「この secret ではこのファイルは開かない」で、暗証番号違い・途中で切れた
    /// ダウンロード・改竄のどれでも同じ答え。意図的にそうしている。区別を教えるのは、
    /// ファイルを持つ者にどの推測が近かったかを教えること。
    public static func open(_ blob: Data, secret: String) -> Data? {
        guard !secret.isEmpty, blob.count > headerBytes + tagBytes else { return nil }
        guard blob.prefix(magic.count) == magic else { return nil }

        let iterationBytes = blob.subdata(in: magic.count..<(magic.count + 4))
        let iterations = iterationBytes.reduce(Int(0)) { ($0 << 8) | Int($1) }
        // ばかげて小さいコストを名乗るファイルは、誰かが編集したファイル。下の認証でも
        // 捕まるが、ここで拒否すればそもそも計算しない。
        guard iterations >= minIterations, iterations <= maxIterations else { return nil }

        let saltStart = magic.count + 4
        let nonceStart = saltStart + saltBytes
        let bodyStart = nonceStart + nonceBytes

        let salt = blob.subdata(in: saltStart..<nonceStart)
        let nonceData = blob.subdata(in: nonceStart..<bodyStart)
        let header = blob.subdata(in: 0..<bodyStart)
        let body = blob.subdata(in: bodyStart..<blob.count)
        let ciphertext = body.subdata(in: 0..<(body.count - tagBytes))
        let tag = body.subdata(in: (body.count - tagBytes)..<body.count)

        return try? {
            let key = try derive(secret: secret, salt: salt, iterations: iterations)
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertext,
                tag: tag
            )
            return try AES.GCM.open(box, using: key, authenticating: header)
        }()
    }

    private static func derive(secret: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        try KDF.Insecure.PBKDF2.deriveKey(
            from: Data(secret.utf8),
            salt: salt,
            using: .sha256,
            outputByteCount: keyBytes,
            rounds: iterations
        )
    }

    private static func header(iterations: Int, salt: Data, nonce: Data) -> Data {
        var data = magic
        var big = UInt32(iterations).bigEndian
        withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
        data.append(salt)
        data.append(nonce)
        return data
    }

    private static let magic = Data([0x4C, 0x4F, 0x57, 0x41, 0x4E, 0x42, 0x4B, 0x01]) // LOWANBK\1
    private static let saltBytes = 16
    private static let nonceBytes = 12
    private static let keyBytes = 32
    private static let tagBytes = 16
    private static let headerBytes = 8 + 4 + 16 + 12
    private static let minIterations = 100_000
    private static let maxIterations = 10_000_000
}
