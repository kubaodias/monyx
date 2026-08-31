package com.monyx.ui.onboarding

import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.sync.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class OnboardingUiState(
    val inviteCode: String = "",
    val memberName: String = "",
    val joining: Boolean = false,
    @StringRes val errorRes: Int? = null,
)

/**
 * Onboarding shows when app.session.isEnrolled() is false. Join calls
 * /auth/enroll on Dispatchers.IO; the server never composes user-facing text,
 * so every failure is mapped here from an error CODE to a Polish string,
 * never shown verbatim.
 */
class OnboardingViewModel(private val app: MonyxApp) : ViewModel() {

    private val session = app.session

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onInviteCodeChange(text: String) {
        _uiState.update { it.copy(inviteCode = text.uppercase(), errorRes = null) }
    }

    fun onNameChange(text: String) {
        _uiState.update { it.copy(memberName = text, errorRes = null) }
    }

    fun join(onEnrolled: () -> Unit) {
        val current = _uiState.value
        if (current.joining) return

        val code = current.inviteCode.trim()
        val name = current.memberName.trim()
        if (code.isEmpty()) {
            _uiState.update { it.copy(errorRes = R.string.onboarding_need_code) }
            return
        }
        if (name.isEmpty()) {
            _uiState.update { it.copy(errorRes = R.string.onboarding_need_name) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(joining = true, errorRes = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { Api.enroll(code, name, Build.MODEL) }
            }
            result.fold(
                onSuccess = { response ->
                    session.save(response.sessionToken, response.householdId, response.memberId)
                    _uiState.update { it.copy(joining = false) }
                    onEnrolled()
                },
                onFailure = { throwable ->
                    _uiState.update { it.copy(joining = false, errorRes = mapError(throwable)) }
                },
            )
        }
    }

    @StringRes
    private fun mapError(throwable: Throwable): Int = when {
        throwable is Api.ApiException -> when (throwable.code) {
            "invite_not_found" -> R.string.onboarding_error_invite_not_found
            "invite_already_used" -> R.string.onboarding_error_invite_already_used
            "invite_expired" -> R.string.onboarding_error_invite_expired
            else -> R.string.onboarding_error_generic
        }
        throwable is IOException -> R.string.onboarding_error_network
        else -> R.string.onboarding_error_generic
    }

    companion object {
        fun factory(app: MonyxApp) = viewModelFactory {
            initializer { OnboardingViewModel(app) }
        }
    }
}
