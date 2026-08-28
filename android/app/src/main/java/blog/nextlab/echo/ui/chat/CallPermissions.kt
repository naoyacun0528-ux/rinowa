package blog.nextlab.echo.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import blog.nextlab.echo.calls.CallKind

/**
 * 通話に要る許可を、必要になった瞬間に頼む。
 *
 * 起動と同時に頼むアプリは消される。だから発信ボタンを押した、あるいは着信に応答した
 * その瞬間にだけ聞く。許可が下りたときにやることは [then] に入れて預ける。
 *
 * 画面の中に4か所（発信、ビデオ発信、応答、ロック画面からの応答）あり、どれも同じ
 * 8行を書いていた。1行で頼めるようにする。
 */
@Composable
internal fun rememberCallPermissions(): CallPermissions {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val audioAndCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val go = pending
        pending = null
        // 結果の map ではなくシステムに聞く。すでに許可済みの権限は map に入らない
        // ことがあり、無い＝拒否と扱ったせいで、ビデオ通話のボタンが何もしなかった
        // （発信もせず、相手も鳴らず、エラーも出ない）。
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        // 通話に必須なのはマイクだけ。カメラを断られてもカメラ off で始まる通話になる。
        if (hasMic) go?.invoke()
    }

    val audioOnly = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val go = pending
        pending = null
        if (granted) go?.invoke()
    }

    return remember(audioAndCamera, audioOnly) {
        CallPermissions(
            setPending = { pending = it },
            askAudio = { audioOnly.launch(Manifest.permission.RECORD_AUDIO) },
            askAudioAndCamera = {
                // 同時に頼む。マイクのあとにカメラを聞くと、通話を始めた人の前に
                // ダイアログが2枚続く。
                audioAndCamera.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                )
            },
        )
    }
}

/** [rememberCallPermissions] が返すもの。 */
internal class CallPermissions(
    private val setPending: (() -> Unit) -> Unit,
    private val askAudio: () -> Unit,
    private val askAudioAndCamera: () -> Unit,
) {
    /** 許可が下りたら [then] を実行する。ビデオならカメラも同じダイアログで頼む。 */
    fun then(kind: CallKind, then: () -> Unit) {
        setPending(then)
        if (kind == CallKind.Video) askAudioAndCamera() else askAudio()
    }
}
