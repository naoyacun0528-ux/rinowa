package blog.nextlab.echo.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import blog.nextlab.echo.backup.BackupRepository
import blog.nextlab.echo.backup.DriveAuthorization
import blog.nextlab.echo.core.model.UserId
import kotlinx.coroutines.launch

/**
 * バックアップ画面と、2つのボタンの裏側。
 *
 * 同意画面をここから出すのは、`drive.appdata` の許可を求めるのにダイアログが要り、
 * ダイアログには activity が要るから。下のリポジトリは activity を知らない
 * （トークンを求め、無ければ失敗する）ようにしてある。画面を出せる唯一の場所が
 * 答えも受け取る。
 *
 * 断られたら裏で再試行しない。許可のダイアログを断った人は意思表示をしていて、
 * 見ていない隙にもう一度聞くのはその扱い方ではない。
 */
@Composable
internal fun BackupRoute(
    backup: BackupRepository?,
    me: UserId?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember { mutableStateOf(BackupUiState(busy = true)) }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        state = if (result.resultCode == android.app.Activity.RESULT_OK) {
            // **繋がった。もう一度押させない。** 許可は取れているので、
            // 次に進める形で戻す。
            BackupUiState(connected = true, hasBackup = state.hasBackup)
        } else {
            BackupUiState(
                hasBackup = state.hasBackup,
                message = "許可されなかったので、バックアップはできません。",
                failed = true,
            )
        }
    }

    /** 許可を確かめ、要るなら同意画面を出す。**画面を出すのは押されたときだけ。** */
    suspend fun connect(interactive: Boolean) {
        val authorization = DriveAuthorization(context)
        when (val outcome = authorization.authorize()) {
            is DriveAuthorization.Outcome.Granted ->
                state = BackupUiState(connected = true, hasBackup = state.hasBackup)

            is DriveAuthorization.Outcome.NeedsConsent ->
                if (interactive) {
                    consent.launch(
                        androidx.activity.result.IntentSenderRequest
                            .Builder(outcome.intent.intentSender)
                            .build(),
                    )
                } else {
                    // 開いただけの人にダイアログは出さない。押されるまで待つ。
                    state = BackupUiState(hasBackup = state.hasBackup)
                }

            is DriveAuthorization.Outcome.Failed ->
                state = BackupUiState(
                    hasBackup = state.hasBackup,
                    message = if (interactive) outcome.message else null,
                    failed = interactive,
                )
        }
    }

    // 開いた時点で、置き場所と控えの有無を静かに1回だけ確かめる。
    // 何も預けていないアカウントに「復元」と出しても、何も差し出していない。
    LaunchedEffect(backup) {
        val existing = backup?.available()?.getOrNull().orEmpty()
        state = BackupUiState(busy = true, hasBackup = existing.isNotEmpty())
        connect(interactive = false)
    }

    /** 失敗を、同意の要求か、人が読める1文かに変える。 */
    suspend fun handle(failure: Throwable) {
        val authorization = DriveAuthorization(context)
        when (val outcome = authorization.authorize()) {
            is DriveAuthorization.Outcome.NeedsConsent -> {
                consent.launch(
                    androidx.activity.result.IntentSenderRequest
                        .Builder(outcome.intent.intentSender)
                        .build(),
                )
                state = BackupUiState(hasBackup = state.hasBackup)
            }

            else -> {
                state = BackupUiState(
                    connected = state.connected,
                    hasBackup = state.hasBackup,
                    message = failure.message ?: "うまくいきませんでした",
                    failed = true,
                )
            }
        }
    }

    BackupScreen(
        state = state,
        onConnectDrive = {
            state = BackupUiState(hasBackup = state.hasBackup, busy = true)
            scope.launch { connect(interactive = true) }
        },
        onBackUp = { secret ->
            val owner = me ?: return@BackupScreen
            val repository = backup ?: return@BackupScreen
            state = BackupUiState(connected = true, hasBackup = state.hasBackup, busy = true)
            scope.launch {
                repository.backUp(owner, secret.toCharArray()).fold(
                    onSuccess = { summary ->
                        state = BackupUiState(
                            connected = true,
                            hasBackup = true,
                            message = "" + summary.messages + "件を保存しました（" +
                                (summary.bytes / 1024) + " KB）。",
                        )
                    },
                    onFailure = { handle(it) },
                )
            }
        },
        onRestore = { secret ->
            val repository = backup ?: return@BackupScreen
            state = BackupUiState(connected = true, hasBackup = true, busy = true)
            scope.launch {
                val newest = repository.available().getOrNull()?.firstOrNull()
                if (newest == null) {
                    state = BackupUiState(
                        connected = true,
                        message = "ドライブにバックアップがありません。",
                        failed = true,
                    )
                    return@launch
                }
                repository.restore(newest, secret.toCharArray()).fold(
                    onSuccess = { count ->
                        state = BackupUiState(
                            connected = true,
                            hasBackup = true,
                            message = "" + count + "件を復元しました。開けなかったメッセージが読めるようになります。",
                        )
                    },
                    onFailure = { handle(it) },
                )
            }
        },
        onBack = onBack,
    )
}
