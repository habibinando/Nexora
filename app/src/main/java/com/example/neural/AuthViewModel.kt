package com.example.neural

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Authenticated(val user: FirebaseUser) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class AuthViewModel(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(
        auth.currentUser?.let { AuthUiState.Authenticated(it) } ?: AuthUiState.Idle
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (user != null) {
            _uiState.value = AuthUiState.Authenticated(user)
        } else {
            _uiState.value = AuthUiState.Idle
        }
    }

    init {
        auth.addAuthStateListener(authListener)
    }

    override fun onCleared() {
        super.onCleared()
        auth.removeAuthStateListener(authListener)
    }

    fun signInWithGoogle(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                val credentialManager = CredentialManager.create(context)
                val serverClientId = context.getString(R.string.default_web_client_id)

                val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val response = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = response.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(firebaseCred).await()
                    val user = authResult.user
                    if (user != null) {
                        try {
                            val firestoreSync = FirestoreSyncManager.create(context)
                            firestoreSync.saveUserProfile(user.displayName, user.email)
                        } catch (e: Exception) {
                            Log.w("AuthViewModel", "User profile save to Firestore notice: ${e.message}")
                        }
                        _uiState.value = AuthUiState.Authenticated(user)
                        onComplete?.invoke(true)
                    } else {
                        _uiState.value = AuthUiState.Error("Gagal mendapatkan akun pengguna")
                        onComplete?.invoke(false)
                    }
                } else {
                    _uiState.value = AuthUiState.Error("Format kredensial tidak didukung")
                    onComplete?.invoke(false)
                }
            } catch (e: GetCredentialCancellationException) {
                _uiState.value = AuthUiState.Idle
                onComplete?.invoke(false)
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Google Sign-In failed", e)
                _uiState.value = AuthUiState.Error(e.message ?: "Autentikasi Google gagal")
                onComplete?.invoke(false)
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _uiState.value = AuthUiState.Idle
    }
}
