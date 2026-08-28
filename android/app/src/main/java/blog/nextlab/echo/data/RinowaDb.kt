package blog.nextlab.echo.data

/**
 * Firestore のコレクション名と項目名を1箇所に。
 *
 * これらの文字列は3箇所で一致している必要がある（このファイル、firestore.rules、
 * firestore.indexes.json）。項目名の打ち間違いはコンパイルでは落ちず、ルールに
 * 弾かれるドキュメントを黙って書くか、もっと悪いと、通ったのに誰も読まないものを書く。
 * せめてアプリ側は1箇所にまとめる。
 */
object RinowaDb {

    object Users {
        const val COLLECTION = "users"
        const val DISPLAY_NAME = "displayName"
        const val PHOTO_URL = "photoUrl"
        const val STATUS_MESSAGE = "statusMessage"

        /**
         * プロフィール画像の内容ハッシュ。
         *
         * よく読む小さな利用者ドキュメントに置く。手元の複製が最新かどうかを、
         * 画像を落とさずに判断できる。
         */
        const val PHOTO_HASH = "photoHash"

        const val INVITE_CODE = "inviteCode"

        /**
         * 画像そのものは別ドキュメント。
         *
         * 数十KBあるのに対し、[DISPLAY_NAME] は名前を引くたびに読む。同じドキュメントに
         * 入れると、名前を並べるだけで全員の写真を落とすことになる。
         */
        const val PUBLIC = "public"
        const val PUBLIC_PHOTO_DOC = "photo"
        const val PHOTO_BYTES = "bytes"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"

        /** クラウド同期する設定。docs/SYNC_AND_BACKUP.md §2。 */
        const val SETTINGS = "settings"
        const val SETTINGS_DOC = "app"

        /** スタンプへの参照。画像そのものは入らない。 */
        const val STICKER_LIBRARY = "stickerLibrary"
        const val STICKER_LIBRARY_DOC = "owned"
    }

    /**
     * このアカウントが使っている端末。
     *
     * push 通知と、あとで Rinowa Direct の信頼済み端末鍵が同じ一覧を使う。無くした端末を
     * 消すときは両方同時に消える必要がある。2つに分けるとずれて、忘れられたほうが
     * 効いてくる。
     */
    object Devices {
        const val COLLECTION = "devices"
        const val FCM_TOKEN = "fcmToken"
        const val PLATFORM = "platform"
        const val OS_API_LEVEL = "osApiLevel"
        const val UPDATED_AT = "updatedAt"

        /** Rinowa Direct 用。あとで。docs/DIRECT_ARCHITECTURE.md §5。 */
        const val PUBLIC_KEY = "publicKey"
        const val REVOKED_AT = "revokedAt"
    }

    object InviteCodes {
        const val COLLECTION = "inviteCodes"
        const val UID = "uid"
        const val CREATED_AT = "createdAt"
    }

    object Conversations {
        const val COLLECTION = "conversations"
        const val TYPE = "type"
        const val TYPE_DIRECT = "direct"
        const val TYPE_GROUP = "group"
        const val TITLE = "title"
        const val MEMBER_IDS = "memberIds"

        /**
         * この会話にいることに同意した人。
         *
         * 始めた人は最初から入っている。招かれた人は友達追加を押すまで入らない。
         * 参加者であることはメッセージが届くという意味で、この一覧に入っていることは
         * 自分から求めたという意味。
         */
        const val ACCEPTED_BY = "acceptedBy"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val LAST_MESSAGE = "lastMessage"
        const val LAST_MESSAGE_AT = "lastMessageAt"

        /**
         * 会話一覧に出すプレビュー。
         *
         * メッセージのサブコレクションの外に本文の断片が出る唯一の場所。ただし同じ
         * 参加者限定のルールの下にあるので、読める人はまったく同じ。行に入らないので
         * 切り詰めてある。
         */
        object LastMessage {
            const val PREVIEW = "preview"
            const val SENDER_ID = "senderId"
            const val KIND = "kind"
            const val SENT_AT = "sentAt"
        }
    }

    object Messages {
        const val COLLECTION = "messages"
        const val SENDER_ID = "senderId"
        const val KIND = "kind"
        const val KIND_TEXT = "text"
        const val KIND_STICKER = "sticker"
        const val KIND_IMAGE = "image"
        const val KIND_CALL = "call"

        /**
         * 参加者の端末だけが本文を読めるメッセージ。
         *
         * ドキュメントには Megolm のイベントしか入らない（本文の項目も、プレビューも、
         * 長さも無い）。**ルールは中身を検査できないが、それが狙い**。firestore.rules を参照。
         */
        const val KIND_ENC = "enc"
        const val CIPHERTEXT = "ciphertext"

        // 通話の記録。どちらの端末も同じ1件を見て、発信側か着信側かで文言だけが変わる。
        const val CALL_KIND = "callKind"
        const val CALL_OUTCOME = "callOutcome"
        const val CALL_SECONDS = "callSeconds"
        const val TEXT = "text"
        const val STICKER_ID = "stickerId"

        /**
         * 写真。自分のバイト列のハッシュで参照する。
         *
         * 画像はこのドキュメントには入らないし、今後も入らない。[MEDIA_THUMB] だけが
         * 意図的な例外で、32px の数KB。往復を待たず、メッセージと同時に画面へ出すため。
         * それ以上の大きさのものは、押されたときに [MEDIA_ID] で取りに行く。
         *
         * docs/MEDIA_ARCHITECTURE.md §4。
         */
        const val MEDIA_ID = "mediaId"
        const val MEDIA_WIDTH = "mediaW"
        const val MEDIA_HEIGHT = "mediaH"
        const val MEDIA_BYTES = "mediaBytes"
        const val MEDIA_THUMB = "mediaThumb"

        /** メッセージ内サムネイルの上限。firestore.rules の規則と一致させる。 */
        const val MAX_THUMB_BYTES = 8 * 1024

        const val SENT_AT = "sentAt"
        const val REACTIONS = "reactions"
        const val REPLY_TO_ID = "replyToId"
        const val REPLY_TO_NAME = "replyToName"
        const val REPLY_TO_EXCERPT = "replyToExcerpt"

        /**
         * 相手がすでに読んだメッセージを送信者が取り消したときに入る。
         *
         * 誰も読んでいないものは丸ごと消す（説明することが無い）。読まれたあとに黙って
         * 消すのは相手の記憶を書き換えることなので、取り消した事実は残す。
         */
        const val RETRACTED_AT = "retractedAt"

        /** firestore.rules の規則と一致。 */
        const val MAX_TEXT_LENGTH = 4000

        /** 会話一覧に出す長さ。1行ぶんだけ。 */
        const val PREVIEW_LENGTH = 80
    }

    object Reads {
        const val COLLECTION = "reads"
        const val LAST_READ_AT = "lastReadAt"
    }

    /**
     * 写真のバイト列。そのバイト列のハッシュを鍵にする。
     *
     * メッセージと分ける。同じ写真を何度送っても保管は1つで済み、スレッドを読むのに
     * 写真を全部引かずに済む。docs/MEDIA_ARCHITECTURE.md。
     */
    object Media {
        const val COLLECTION = "media"
        const val BYTES = "bytes"
        const val BYTE_COUNT = "byteCount"
        const val CREATED_AT = "createdAt"
    }

    object Stickers {
        const val COLLECTION = "stickers"
        const val OWNER_ID = "ownerId"
        const val BYTES = "bytes"
        const val CONTENT_HASH = "contentHash"
        const val WIDTH_PX = "widthPx"
        const val HEIGHT_PX = "heightPx"
        const val FORMAT = "format"
        const val CREATED_AT = "createdAt"
    }

    object Feedback {
        const val COLLECTION = "feedback"
        const val AUTHOR_ID = "authorId"
        const val TITLE = "title"
        const val BODY = "body"
        const val CATEGORY = "category"
        const val CREATED_AT = "createdAt"

        const val VOTES = "votes"
        const val VOTE_CREATED_AT = "createdAt"

        const val MAX_TITLE_LENGTH = 120
        const val MAX_BODY_LENGTH = 4000
    }

    object Settings {
        const val HAPTICS_ENABLED = "hapticsEnabled"
        const val HAPTIC_INTENSITY = "hapticIntensity"
        const val ANALYTICS_OPTED_OUT = "analyticsOptedOut"

        /**
         * 通知に本文を出すか。
         *
         * 既定は true（他のメッセンジャーと同じ期待に合わせる）。push サーバーが読むのは
         * **受け取る側**の設定で、送る側のものではない。自分のロック画面に何が出るかは
         * 自分が決める。
         */
        const val NOTIFICATION_SHOWS_BODY = "notificationShowsBody"
        const val UPDATED_AT = "updatedAt"
    }

    object StickerLibrary {
        const val OWNED = "ownedStickerIds"
        const val FAVOURITES = "favoriteStickerIds"
        const val RECENT = "recentStickerIds"
        const val UPDATED_AT = "updatedAt"
    }
}
