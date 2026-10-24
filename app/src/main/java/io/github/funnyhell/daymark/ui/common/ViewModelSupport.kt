package io.github.funnyhell.daymark.ui.common

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import io.github.funnyhell.daymark.AppContainer
import io.github.funnyhell.daymark.DaymarkApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import android.util.Log
import io.github.funnyhell.daymark.R

/** A one-off message for the snackbar, optionally with an action such as "Undo". */
data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

abstract class AppViewModel(application: Application) : AndroidViewModel(application) {
    protected val app: DaymarkApplication get() = getApplication()
    protected val container: AppContainer get() = app.container

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    protected fun string(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    protected fun say(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, actionLabel, onAction))
    }

    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.e("Daymark", "Operation failed", e)
        say(string(R.string.error_generic, e.message ?: e.javaClass.simpleName))
    }

    /** Launches database work; failures are reported to the user instead of being swallowed. */
    protected fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch(errorHandler) { block() }
    }
}

fun <VM : ViewModel> appViewModelFactory(create: (Application, SavedStateHandle) -> VM): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = this[APPLICATION_KEY] as Application
            create(app, createSavedStateHandle())
        }
    }
