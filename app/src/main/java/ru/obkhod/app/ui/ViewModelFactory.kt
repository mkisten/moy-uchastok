package ru.obkhod.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Простая фабрика ViewModel без DI-фреймворков. */
inline fun <reified VM : ViewModel> viewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
